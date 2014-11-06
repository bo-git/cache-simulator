package Object;

import java.util.Vector;

/**
 * Created by Bo on 11/6/14.
 */
public class Bus {

    static int numOfProc;
    static Vector<Processor> processors;

    public static void setNumOfProc(int num) {
        numOfProc = num;
    }

    public static void setProcessors(Vector<Processor> procs) {
        processors = procs;
    }

    public static int read(String address, int currProcessor) {
        Cache cache;
        int data = 0;
        for (int i = 0; i < numOfProc; i++) {
            if(i == currProcessor)
                continue;

            cache = processors.get(i).getCache();
            data = cache.busRead(address);
        }
        return data;
    }

    public static void readEx(String address, int currProcessor) {
        Cache cache;
        for (int i = 0; i < numOfProc; i++) {
            if(i == currProcessor)
                continue;

            cache = processors.get(i).getCache();
            cache.busReadEx(address);
        }
    }

}
