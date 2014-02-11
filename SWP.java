/*===============================================================*
 *  File: SWP.java                                               *
 *                                                               *
 *  This class implements the sliding window protocol            *
 *  Used by VMach class					         *
 *  Uses the following classes: SWE, Packet, PFrame, PEvent,     *
 *                                                               *
 *  Author: Professor SUN Chengzheng                             *
 *          School of Computer Engineering                       *
 *          Nanyang Technological University                     *
 *          Singapore 639798                                     *
 *===============================================================*/
/**
 * User: Danyang
 * Date: 2/11/14
 * Time: 6:35 PM
 */

public class SWP {

    /*========================================================================*
     the following are provided, do not change them!!
     *========================================================================*/
    //the following are protocol constants.
    public static final int MAX_SEQ = 7;
    public static final int NR_BUFS = (MAX_SEQ + 1)/2;

    // the following are protocol variables
    private int oldest_frame = 0;
    private PEvent event = new PEvent();
    private Packet out_buf[] = new Packet[NR_BUFS];

    //the following are used for simulation purpose only
    private SWE swe = null;
    private String sid = null;

    //Constructor
    public SWP(SWE sw, String s){
        swe = sw;
        sid = s;
    }

    //the following methods are all protocol related
    private void init(){
        for (int i = 0; i < NR_BUFS; i++){
            out_buf[i] = new Packet();
        }
    }

    private void wait_for_event(PEvent e){
        swe.wait_for_event(e); //may be blocked
        oldest_frame = e.seq;  //set timeout frame seq
    }

    private void enable_network_layer(int nr_of_bufs) {
        //network layer is permitted to send if credit is available
        swe.grant_credit(nr_of_bufs);
    }

    private void from_network_layer(Packet p) {
        swe.from_network_layer(p);
    }

    private void to_network_layer(Packet packet) {
        swe.to_network_layer(packet);
    }

    private void to_physical_layer(PFrame fm)  {
        System.out.println("SWP: Sending frame: seq = " + fm.seq +
                " ack = " + fm.ack + " kind = " +
                PFrame.KIND[fm.kind] + " info = " + fm.info.data );
        System.out.flush();
        swe.to_physical_layer(fm);
    }

    private void from_physical_layer(PFrame fm) {
        PFrame fm1 = swe.from_physical_layer();
        fm.kind = fm1.kind;
        fm.seq = fm1.seq;
        fm.ack = fm1.ack;
        fm.info = fm1.info;
    }


    /*===========================================================================*
        implement your Protocol Variables and Methods below:
     *==========================================================================*/
    private boolean no_nak = true;
    private Packet in_buf[] = new Packet[NR_BUFS];

    /**
     * Initilize inf_buf[]
     */
    private void init_in_buff() {
        for (int i=0; i<NR_BUFS; i++) {
            this.in_buf[i] = new Packet();
        }
    }

    private boolean arrived[] = new boolean[NR_BUFS];

    /**
     * Initialize arrived[]
     */
    private void init_arrived() {
        for (int i=0; i<NR_BUFS; i++) {
           this.arrived[i] = false;
        }
    }

    /**
     * Return true if a <= b < c circularly; false otherwise.
     * @param a int
     * @param b int
     * @param c int
     * @return boolean
     */
    private boolean between(int a, int b, int c) {
        if (a<=b&&b<c||c<a&&c<=b||b<c&&c<a) {
            return true;
        }
        return false;
    }
    /**
     * Send the frame from output buffer to the physical layer
     * @param frame_kind DATA, ACK, NAK
     * @param frame_number sequence number
     * @param frame_expected to calculate the acknowledgement number
     * @param out_buffer from which the output frame is extracted
     */
    private void send_frame(int frame_kind, int frame_number, int frame_expected, Packet out_buffer[]) {
        PFrame frame = new PFrame(); // scratch
        frame.kind = frame_kind; // DATA, ACK, NAK
        if (frame_kind==PFrame.DATA)  {
            frame.info = out_buffer[frame_number%NR_BUFS];
        }
        frame.seq = frame_number;
        frame.ack = (frame_expected+MAX_SEQ)%(MAX_SEQ+1); // one before the frame_expected
        if (frame_kind==PFrame.NAK) {
            this.no_nak = false;
        }
        this.to_physical_layer(frame);
        if (frame_kind==PFrame.DATA) { // start timer only after sending
            this.start_timer(frame_number%NR_BUFS);
        }
        this.stop_ack_timer();
    }

    /**
     * Sliding Window Protocol (i.e. protocol 6)
     */
    public void protocol6() {
        this.init(); // initialize the out buffer
        this.init_in_buff(); // initialize the input buffer
        this.init_arrived(); // initialize the arrived array
        // Send
        // e.g. 0 1 2 | 3 4 5 6 | 7 8
        int ack_expected = 0; // lower edge to the sender's window
        int next_frame_to_send = 0; // upper edge of sender's windows + 1
        // Receive
        int frame_expected = 0; // lower edge of receiver's window
        int too_far = NR_BUFS; // upper edge of receiver's window + 1

        int i; // index into buffer array
        PFrame frame_received = new PFrame();  // Scratch
        int nr_output_buffered = 0; // how many output buffers currently used

        this.enable_network_layer(NR_BUFS);
        while(true) {
            wait_for_event(event);
            switch(event.type) {
                case (PEvent.NETWORK_LAYER_READY): // sending out
                    nr_output_buffered++;
                    this.from_network_layer(out_buf[next_frame_to_send%NR_BUFS]);
                    this.send_frame(PFrame.DATA, next_frame_to_send, frame_expected, this.out_buf);
                    next_frame_to_send++;
                    break;
                case (PEvent.FRAME_ARRIVAL ): // receiving
                    this.from_physical_layer(frame_received);
                    if(frame_received.kind==PFrame.DATA) {
                        // An undamanged frame has arrived
                        if(frame_received.seq!=frame_expected&&this.no_nak) {
                            this.send_frame(PFrame.NAK, 0, frame_expected, this.out_buf); // seq number not expected
                        }
                        else {
                            this.start_ack_timer();
                        }

                        if(this.between(frame_expected, frame_received.seq, too_far)&&!arrived[frame_received.seq%NR_BUFS]) {
                            // frames received may be in any order
                            this.arrived[frame_received.seq%NR_BUFS] = true;
                            this.in_buf[frame_received.seq%NR_BUFS] = frame_received.info;
                            while(arrived[frame_expected%NR_BUFS]){
                                /*
                                Pass frames and advance window.
                                [expected, .., frame_received, .., too far]
                                 */
                                this.to_network_layer(this.in_buf[frame_expected%NR_BUFS]);
                                this.no_nak = true;
                                this.arrived[frame_expected%NR_BUFS] = false;
                                frame_expected++;
                                too_far++;
                                this.start_ack_timer();
                            }
                        }
                    }
                    if(frame_received.kind==PFrame.NAK&&this.between(ack_expected, (frame_received.ack+1)%(MAX_SEQ+1), next_frame_to_send)) {
                        /*
                        frame_received.ack is all frames received correctly received and acknowledged
                        nak for the
                         */
                        this.send_frame(PFrame.DATA, (frame_received.seq+1)%(MAX_SEQ+1), frame_expected, this.out_buf);
                    }

                    while(between(ack_expected, frame_received.ack, next_frame_to_send)) {
                        // acknowledgement received for the [lower, .., ack]
                        nr_output_buffered--;
                        stop_timer(ack_expected%NR_BUFS);
                        ack_expected++;
                    }
                    break;
                case (PEvent.CKSUM_ERR):
                    if(this.no_nak) {
                        this.send_frame(PFrame.NAK, 0, frame_expected, this.out_buf); // damaged frame
                    }
                    break;
                case (PEvent.TIMEOUT):
                    this.send_frame(PFrame.DATA, this.oldest_frame, frame_expected, this.out_buf);
                    break;
                case (PEvent.ACK_TIMEOUT):
                    this.send_frame(PFrame.ACK, 0, frame_expected, this.out_buf);
                    break;
                default:
                    System.out.println("SWP: undefined event type = "  + event.type);
                    System.out.flush();
            }
            // Enable Disable buffer
        }
    }

    /* Note: when start_timer() and stop_timer() are called,
        the "seq" parameter must be the sequence number, rather
        than the index of the timer array,
        of the frame associated with this timer,
    */

    private void start_timer(int seq) {

    }

    private void stop_timer(int seq) {

    }

    private void start_ack_timer( ) {

    }

    private void stop_ack_timer() {

    }

}//End of class

    /* Note: In class SWE, the following two public methods are available:
       . generate_acktimeout_event() and
       . generate_timeout_event(seqnr).

       To call these two methods (for implementing timers),
       the "swe" object should be referred as follows:
         swe.generate_acktimeout_event(), or
         swe.generate_timeout_event(seqnr).
    */


