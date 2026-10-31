/*
 * Author: Khup Sawm, a1924824
 */

package comp3011;


import org.bytedeco.javacv.Frame;


// Draws speech to text subtitles onto each frame while enabled
// Stub for now as a placeholder will work on the command line option first
public class FrameSub extends FrameProcessor {
	
	// True while subtitle should be drawn. Written on the FX thread, read on the effect thread.
	private volatile boolean enabled = true;
	
	public boolean isEnabled() {
		return enabled;
	}
	
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
	
	@Override
	public void process(Frame frame, InfoFrame info) throws Exception {
		if (!enabled) {
			return;
		}
		
		// TODO Auto-generated method stub
		
	}	

}
