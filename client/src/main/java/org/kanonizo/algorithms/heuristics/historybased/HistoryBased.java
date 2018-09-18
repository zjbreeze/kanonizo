package org.kanonizo.algorithms.heuristics.historybased;

import com.scythe.instrumenter.InstrumentationProperties.Parameter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.kanonizo.algorithms.TestCasePrioritiser;
import org.kanonizo.exception.SystemConfigurationException;
import org.kanonizo.framework.TestCaseStore;
import org.kanonizo.framework.objects.TestCase;

public abstract class HistoryBased extends TestCasePrioritiser {

  private static final String PROJECT_ID = "project_id";
  private static final String VERSION_ID = "version_id";
  private static final String NUM_REVISIONS = "num_revisions";
  private static final String REVISION_ID = "revision_id";
  private static final String TEST_NAME = "test_name";
  private static final String TEST_RUNTIME = "test_runtime";
  private static final String TEST_OUTCOME = "test_outcome";
  private static final String TEST_STACK_TRACE = "test_stack_strace";



  @Parameter(key = "history_file", description = "For history based techniques we must provide a readable file containing the history of the test cases so that we can calculate the number of failures, time since last failure etc",
  category = "history")
  public static File HISTORY_FILE = null;

  private Map<TestCase, List<Execution>> historyData = new HashMap<>();

  public void init(List<TestCase> testCases) {
    if(HISTORY_FILE == null || !HISTORY_FILE.exists()){
      throw new SystemConfigurationException("In order to execute a history based technique you must provide a -Dhistory_file option in the command line");
    }
    readHistoryFile();
  }

  private void readHistoryFile(){
    try {
      CSVParser parser = new CSVParser(new FileReader(HISTORY_FILE), CSVFormat.DEFAULT.withHeader(PROJECT_ID,VERSION_ID, NUM_REVISIONS, REVISION_ID, TEST_NAME, TEST_RUNTIME, TEST_OUTCOME, TEST_STACK_TRACE).withSkipHeaderRecord(true));
      Iterator<CSVRecord> it = parser.iterator();
      while(it.hasNext()){
        CSVRecord next = it.next();
        String testCaseName = next.get(TEST_NAME);
        String testClass = testCaseName.split("::")[0];
        String testMethod = testCaseName.split("::")[1];
        String testString = testMethod +"("+testClass+")";
        TestCase tc = TestCaseStore.with(testString);
        if(!historyData.containsKey(tc)){
          historyData.put(tc, new ArrayList<>());
        }
        int ind = Math.abs(Integer.parseInt(next.get(REVISION_ID)));
        if(ind == 0){
          continue;
        }
        Throwable cause = null;
        if(!next.get(TEST_STACK_TRACE).equals("")){
          // parse throwable into object here
        }
        historyData.get(tc).add(Math.min(ind, historyData.get(tc).size()), new Execution(Long.parseLong(next.get(TEST_RUNTIME)), next.get(TEST_OUTCOME).equals("pass"), cause));
      }
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public int getNumExecutions(TestCase tc){
    return !historyData.containsKey(tc) ? 0 :  historyData.get(tc).size();
  }

  public int getNumFailures(TestCase tc){
    return !historyData.containsKey(tc) ? 0 : (int) historyData.get(tc).stream().filter(ex -> !ex.isPassed()).count();
  }

  public boolean hasFailed(TestCase tc){
    return historyData.containsKey(tc) && historyData.get(tc).stream().anyMatch(ex -> !ex.isPassed());
  }

  public int getTimeSinceLastFailure(TestCase tc){
    if(!hasFailed(tc)){
      return Integer.MAX_VALUE;
    }
    return historyData.get(tc).indexOf(historyData.get(tc).stream().filter(ex -> !ex.isPassed()).findFirst().get());
  }

  public class Execution{
    private long executionTime;
    private boolean passed;
    private Throwable failureCause;
    public Execution(long executionTime, boolean passed, Throwable failureCause){
      this.executionTime = executionTime;
      this.passed = passed;
      this.failureCause = failureCause;
    }

    public long getExecutionTime(){
      return executionTime;
    }

    public boolean isPassed(){
      return passed;
    }

    public Throwable getFailureCause(){
      return failureCause;
    }
  }

}

