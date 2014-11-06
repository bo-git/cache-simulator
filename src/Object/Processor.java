package Object;

import Protocol.MESI;
import Protocol.MSI;

/**
 * Created by Bo on 11/6/14.
 */
public class Processor {

    Cache cache;
    int cacheProtocol;
    int cacheState;
    int processorNum;

    public Processor(int cacheSize, int blockSize, int associativity, int protocol, int procNum) {
        cache = new Cache().getInstance(cacheSize, blockSize, associativity, protocol, processorNum);
        cacheProtocol = protocol;
        processorNum = procNum;
        if(protocol == MESI.PROTOCOL)
            cacheState = MESI.STATE_INVALID;
        else
            cacheState = MSI.STATE_INVALID;
    }

    public void write(String address) {
        cache.processorWriteCache(address);
    }

    public void read(String address) {
        cache.processorReadCache(address);
    }

    public Cache getCache() {
        return cache;
    }

}
