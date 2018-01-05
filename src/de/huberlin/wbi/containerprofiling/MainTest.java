package de.huberlin.wbi.containerprofiling;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Created by Carl Witt on 10.08.17.
 *
 * @author Carl Witt (cpw@posteo.de)
 */
public class MainTest {
    @org.junit.Test
    public void main() throws Exception {
//        Main.startCAdvisor();
//        Main.startCuneiformServer();

//        String workflowname = "variant-call";
//        String workflowname = "wordcount";
        String workflowname = "use-memory";

        String workflowContent = Main.readWorkflowFromFile(String.format("workflows/%s.cf",workflowname));
//        String workflowContent = Main.memoryUseExampleWorkflow;

        Path buildDir = Paths.get(String.format("%s-%s", workflowname, System.currentTimeMillis()));
        System.out.println("buildDir = " + buildDir);
        Main.runWorkflow(workflowContent, buildDir);
    }

}