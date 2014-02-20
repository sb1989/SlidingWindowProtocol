/*===============================================================*
 *  File: SWP.java                                               *
 *                                                               *
 *  This class implements the sliding window protocol            *
 *  Used by VMach class					                         *
 *  Uses the following classes: SWE, Packet, PFrame, PEvent,     *
 *                                                               *
 *  Author: Professor SUN Chengzheng                             *
 *          School of Computer Engineering                       *
 *          Nanyang Technological University                     *
 *          Singapore 639798                                     *
 *===============================================================*/

import java.util.Timer;
import java.util.TimerTask;

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
    // Sending Receiving
    private boolean no_nak = true;
    private Packet in_buf[] = new Packet[NR_BUFS];

    // Timers
    private static final int NORMAL_TIMEOUT = 600;
    private static final int ACK_TIMEOUT = 300;
    private Timer normal_timers[] = new Timer[NR_BUFS]; // one-time timer; new Timer() every time
    private Timer ack_timer; // one-time timer; new Timer() every time


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
     * Return true if circularly; false otherwise.
     * see if it falls within the window
     * following convention of b \in [a, c)
     * @param a int
     * @param b int
     * @param c int
     * @return boolean
     */
    private boolean between(int a, int b, int c) {
        // normal situation
        if(a<c) {
            return a<=b&&b<c;
        }
        else if (c<a) {
            return a<=b || b<c;
        }
        return false;


    }

    /**
     * Circular increment of a sequence number over the sequence number space
     * @param seq sequence number
     * @return seuqnce number + 1
     */
    private int inc(int seq) {
        return (seq+1)%(MAX_SEQ+1);
    }

    /**
     * Send the frame from output buffer to the physical layer
     * Piggybacking the ACK
     * @param frame_kind DATA, ACK, NAK
     * @param frame_number sequence number, ACK NAK frame by default frame_number = 0;
     * @param frame_expected to calculate the acknowledgement number (the one before it)
     * @param out_buffer from which the output frame is extracted
     */
    private void send_frame(int frame_kind, int frame_number, int frame_expected, Packet out_buffer[]) {
        PFrame frame = new PFrame(); // scratch
        frame.kind = frame_kind; // DATA, ACK, NAK
        if (frame_kind==PFrame.DATA)  {
            frame.info = out_buffer[frame_number%NR_BUFS];
        }
        frame.seq = frame_number;
        frame.ack = (frame_expected+MAX_SEQ)%(MAX_SEQ+1); // one before the frame_expected // piggybacking the ack
        if (frame_kind==PFrame.NAK) {
            this.no_nak = false;
        }
        this.to_physical_layer(frame);
        if (frame_kind==PFrame.DATA) { // start timer only after sending
            this.start_timer(frame_number); // frame number correspond to the sequence number
        }
        this.stop_ack_timer(); // piggybacked
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
        int next_frame_to_send = 0; // upper edge of sender's windows + 1 // sequence number
        // Receive
        int frame_expected = 0; // lower edge of receiver's window
        int too_far = NR_BUFS; // upper edge of receiver's window + 1

        PFrame frame_received = new PFrame();  // Scratch
        // int nr_output_buffered = 0; // how many output buffers currently used // it is written but never read

        this.enable_network_layer(NR_BUFS);
        while(true) {
            wait_for_event(event);
            switch(event.type) {
                case (PEvent.NETWORK_LAYER_READY): // sending out
                    /*
                    Whenever a new packet arrives from the network layer, it is given the next highest sequence number, and
                    the upper edge of the window is advanced by one
                     */
                    // nr_output_buffered++;
                    this.from_network_layer(out_buf[next_frame_to_send%NR_BUFS]);
                    this.send_frame(PFrame.DATA, next_frame_to_send, frame_expected, this.out_buf);
                    next_frame_to_send = inc(next_frame_to_send);
                    break;
                case (PEvent.FRAME_ARRIVAL ): // receiving
                    this.from_physical_layer(frame_received);
                    if(frame_received.kind==PFrame.DATA) {
                        // An undamanged frame has arrived
                        if(frame_received.seq!=frame_expected&&this.no_nak) {
                            // suspect losing frame if the frame is not in order
                            this.send_frame(PFrame.NAK, 0, frame_expected, this.out_buf); // seq number not expected
                        }
                        else {
                            this.start_ack_timer();
                        }

                        if(this.between(frame_expected, frame_received.seq, too_far)&&!arrived[frame_received.seq%NR_BUFS]) {
                            // frames received may be in any order
                            this.arrived[frame_received.seq%NR_BUFS] = true;
                            this.in_buf[frame_received.seq%NR_BUFS] = frame_received.info;

                            while(this.arrived[frame_expected%NR_BUFS]){
                                /*
                                Pass frames and advance window.
                                [expected, .., frame_received, .., too far]

                                When a frame whose sequence number is equal to the lower edge of the window
                                is received, it is passed to the network layer, an acknowledgement is generated (timer start),
                                and the window is rotated by one.

                                Notice: Network Layer must receive the packet in order
                                 */
                                this.to_network_layer(this.in_buf[frame_expected%NR_BUFS]);
                                this.no_nak = true;
                                this.arrived[frame_expected%NR_BUFS] = false;
                                // frame_expected++; // should do circular increment
                                frame_expected = this.inc(frame_expected);
                                too_far = this.inc(too_far);
                                this.start_ack_timer();
                            }
                        }
                    }
                    if(frame_received.kind==PFrame.NAK&&between(ack_expected, (frame_received.ack+1)%(MAX_SEQ+1), next_frame_to_send)) {
                        /*
                        frame_received.ack is all frames received correctly received and acknowledged
                        nak for the
                         */
                        this.send_frame(PFrame.DATA, (frame_received.ack+1)%(MAX_SEQ+1), frame_expected, this.out_buf); // retransmit the lost DATA
                    }

                    while(between(ack_expected, frame_received.ack, next_frame_to_send)) {
                        /*
                        When an acknowledgement comes in, the lower edge is advanced by one.
                        Acknowledgement received for the series of frames [lower, .., ack] (ACK not for single frame)
                         */
                        // nr_output_buffered--;
                        stop_timer(ack_expected%NR_BUFS);
                        ack_expected = this.inc(ack_expected); // looping until the ack_expected == frame_received.ack + 1
                        this.enable_network_layer(1); // grant credit to network_layer // suspect losing frame if the frame is not in order
                    }
                    break;
                case (PEvent.CKSUM_ERR):
                    if(this.no_nak) {
                        this.send_frame(PFrame.NAK, 0, frame_expected, this.out_buf); // frame DATA damaged
                    }
                    break;
                case (PEvent.TIMEOUT):
                    this.send_frame(PFrame.DATA, this.oldest_frame, frame_expected, this.out_buf);
                    break;
                case (PEvent.ACK_TIMEOUT):
                    this.send_frame(PFrame.ACK, 0, frame_expected, this.out_buf); // retransmit ACK if have waited too long for piggybacking
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

    /**
     * Start a normal timer for frame
     * @param seq sequence number
     */
    private void start_timer(int seq) {
        this.stop_timer(seq);
        this.normal_timers[seq%NR_BUFS] = new Timer();
        this.normal_timers[seq%NR_BUFS].schedule(new NormalTimerTask(seq), NORMAL_TIMEOUT);
    }

    /**
     * Stop a normal timer for frame
     * @param seq sequence number
     */
    private void stop_timer(int seq) {
        if(this.normal_timers[seq%NR_BUFS]!=null) {
            this.normal_timers[seq%NR_BUFS].cancel();
            // this.normal_timers[seq%NR_BUFS] = null;
        }
    }

    /**
     * Start a ack timer for a frame
     */
    private void start_ack_timer() {
        this.stop_ack_timer();
        this.ack_timer = new Timer();
        this.ack_timer.schedule(new AckTimerTask(), ACK_TIMEOUT);
    }

    /**
     * Stop a ack timber for a frame
     */
    private void stop_ack_timer() {
        if(this.ack_timer!=null) {
            this.ack_timer.cancel();
            // this.ack_timer = null;
        }
    }

    // Internal Classes
    private class NormalTimerTask extends TimerTask {
        private int seq;

        private NormalTimerTask(int seq) {
            super(); // following python convention
            this.seq = seq;
        }

        @Override
        public void run() {
            SWP.this.stop_timer(this.seq);
            SWP.this.swe.generate_timeout_event(seq);
        }
    }

    private class AckTimerTask extends TimerTask {
        @Override
        public void run() {
            SWP.this.stop_ack_timer();
            SWP.this.swe.generate_acktimeout_event();
        }
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


