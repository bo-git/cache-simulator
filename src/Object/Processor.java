package Object;

import Protocol.MESI;
import Protocol.MSI;

import java.io.IOException;

/**
 * Created by Bo on 11/6/14.
 */
public class Processor {

    Cache cache;
    int cacheProtocol;
    int cacheState;
    int processorNum;

    public Processor(int cacheSize, int blockSize, int associativity, int protocol, int procNum, boolean isUniProc, String filename) {
        cache = new Cache().getInstance(cacheSize, blockSize, associativity, protocol, procNum, isUniProc, filename);
        cacheProtocol = protocol;
        processorNum = procNum;
        if(protocol == MESI.PROTOCOL)
            cacheState = MESI.STATE_INVALID;
        else
            cacheState = MSI.STATE_INVALID;
    }

    public void store(String address, int currCycle) {
        cacheSnoopBus(currCycle);
        cache.processorWriteCache(address);
    }

    public void load(String address, int currCycle) {
        cacheSnoopBus(currCycle);
        cache.processorReadCache(address);
    }

    public void fetch() {}

    public void cacheSnoopBus(int currCycle) {
        cache.snoopBus(currCycle);
    }

    public void createLog() throws IOException{
        cache.createlog();
    }

    public boolean isCacheBlock() {
        return cache.isCacheWaitingForResult();
    }
}
