package Object;

/**
 * Created by Bo on 11/6/14.
 */
public class CacheLine {

    int[] data;

    String tag; //not important, just to store the info
    private int validBit;
    private int dirtyBit;
    private int blockState; //the protocol state

    public CacheLine(int blockSize, String tagString) {
        data = new int[blockSize];
        tag = tagString;
        validBit = 0;
        dirtyBit = 0;
    }

    public int getDataAtPosition(int position) {
        return data[position];
    }

    public void setDataAtPosition(int position) {
        data[position] = 1;
    }

    public void setBlockState(int state) {
        blockState = state;
    }

    public int getBlockState() {
        return blockState;
    }

    public boolean isDirtyBit() {
        return (dirtyBit == 1) ? true : false;
    }

    public void resetDirtyBit() {
        this.dirtyBit = 0;
    }

    public void setDirtyBit() {
        this.dirtyBit = 1;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String s) {
        tag = s;
    }
}
