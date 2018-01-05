package de.huberlin.wbi.containerprofiling;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Created by Carl Witt on 10.08.17.
 *
 * @author Carl Witt (cpw@posteo.de)
 */
public class CAdvisorMonitorTest {
    @Test
    public void run() throws Exception {
        CAdvisorMonitor c = new CAdvisorMonitor("localhost:8080", "zebra-123-dahoo", new File("/dev/zero"));
        System.out.println("c.url = " + c.url);
    }

}