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
    private int usageCount;

    public CacheLine(int blockSize, String tagString, int state) {
        data = new int[blockSize];
        tag = tagString;
        validBit = 0;
        dirtyBit = 0;
        usageCount = 0;
        blockState = state;
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

    public String getTag() {
        return tag;
    }

    public void incUsageCount() { usageCount ++; }

    public int getUsageCount() { return usageCount; }

    public void setUsageCount(){
        usageCount = 1;
    }

    public void updateCacheLine(String tag, int offset, int blockState) {
        this.tag = tag;
        data[offset] = 1;
        this.blockState = blockState;
        this.usageCount = 1;
    }

}
