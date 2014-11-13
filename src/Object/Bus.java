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
        boolean isResultExist = false;
        for (int i = 0; i < numOfProc; i++) {
            if(i == currProcessor)
                continue;

            cache = processors.get(i).getCache();
            int data = cache.busRead(address);
            if(data != 0) isResultExist = true;

        }
        return isResultExist ? 1 : 0;
    }

    public static int readEx(String address, int currProcessor) {
        Cache cache;
        boolean isResultExist = false;
        for (int i = 0; i < numOfProc; i++) {
            if(i == currProcessor)
                continue;

            cache = processors.get(i).getCache();
            int data = cache.busReadEx(address);
            if(data != 0) isResultExist = true;
        }
        return isResultExist ? 1 : 0;
    }

}
