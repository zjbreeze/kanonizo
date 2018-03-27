package org.kanonizo.algorithms.faultprediction;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.scythe.instrumenter.InstrumentationProperties.Parameter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kanonizo.Framework;
import org.kanonizo.algorithms.TestCasePrioritiser;
import org.kanonizo.algorithms.heuristics.comparators.GreedyComparator;
import org.kanonizo.annotations.Algorithm;
import org.kanonizo.annotations.OptionProvider;
import org.kanonizo.annotations.Prerequisite;
import org.kanonizo.framework.ClassStore;
import org.kanonizo.framework.objects.ClassUnderTest;
import org.kanonizo.framework.objects.Line;
import org.kanonizo.framework.objects.TestCase;
import org.kanonizo.util.Util;

@Algorithm(readableName = "schwa")
public class Schwa extends TestCasePrioritiser {

  private static Logger logger = LogManager.getLogger(Schwa.class);

  @Parameter(key = "schwa_revisions_weight", description = "How much influence the number of revisions to a file should have over its likelihood of containing a fault", category = "schwa")
  public static double REVISIONS_WEIGHT = 0.3;

  @Parameter(key = "schwa_authors_weight", description = "How much influence the number of authors= who have committed to a file should have over its likelihood of containing a fault", category = "schwa")
  public static double AUTHORS_WEIGHT = 0.2;

  @Parameter(key = "schwa_fixes_weight", description = "How much influence the number of times a file has been associated with a \"fix\" should have over its likelihood of containing a fault", category = "schwa")
  public static double FIXES_WEIGHT = 0.5;

  @Parameter(key = "schwa_secondary_objective", description = "Since Schwa tells us the likelihood of each class/method containing a fault, we discover the test cases that execute that area of code. However, a secondary objective can allow us to prioritise test cases within the set of test cases that cover a faulty objective", category = "schwa", hasOptions = true)
  public static Comparator<TestCase> COMPARATOR;

  @Parameter(key = "classes_per_group", description = "Schwa tells us the likelihood of classes containing a fault - prioritising using this information involves finding all tests that execute a faulty class. This variable controls how many classes to \"group\" together when finding test cases to prioritise", category = "schwa")
  public static int CLASSES_PER_GROUP = 1;

  private List<SchwaClass> classes;
  private List<SchwaClass> active = new ArrayList<>();
  private List<TestCase> testCasesForActive = new ArrayList<>();

  public void init(List<TestCase> testCases) {
    super.init(testCases);
    // run schwa
    try {
      File temp = File.createTempFile("schwa-json-output", ".tmp");
      Framework.getInstance().getDisplay().notifyTaskStart("Running Schwa", true);
      runProcess(temp, "schwa", fw.getRootFolder().getAbsolutePath(), "-j");
      Framework.getInstance().getDisplay().reportProgress(1,1);
      Gson gson = new Gson();
      SchwaRoot root = gson.fromJson(new FileReader(temp), SchwaRoot.class);
      classes = root.getChildren().stream().filter(cl -> cl.getPath().endsWith(".java"))
          .collect(Collectors.toList());
      // sort classes by probability of containing a fault
      Collections.sort(classes, Comparator.comparingDouble(o -> o.getProb()));
      temp.delete();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public TestCase selectTestCase(List<TestCase> testCases) {
    while (testCasesForActive.size() == 0) {
      if (classes.size() > 0) {
        // pick next n classes to prioritise tests for, either CLASSES_PER_GROUP or all remaining classes
        active.clear();
        for(int i = 0; i < Math.min(CLASSES_PER_GROUP, classes.size()); i++){
          active.add(classes.get(i));
        }
        classes.removeAll(active);
        // grab covering test cases
        testCasesForActive = getTestsCoveringClasses(testCases, active);
      } else {
        // if there are no more classes reported by Schwa, just add test cases in the order we found them
        testCasesForActive = testCases;
      }
      if (COMPARATOR != null) {
        // secondary objective sort for test cases
        testCasesForActive.sort(COMPARATOR);
      }
    }
    TestCase next = testCasesForActive.get(0);
    testCasesForActive.remove(next);
    return next;
  }

  private List<TestCase> getTestsCoveringClasses(List<TestCase> candidates,
      List<SchwaClass> classes) {
    List<TestCase> tests = new ArrayList<>();
    Iterator<SchwaClass> it = classes.iterator();
    while (it.hasNext()){
      SchwaClass cl = it.next();
      String filePath = cl.getPath();
      tests.addAll(getTestsCoveringClass(candidates, filePath));
    }
    return tests;
  }

  private List<TestCase> getTestsCoveringClass(List<TestCase> candidates, String filePath) {
    String fullPath = fw.getRootFolder().getAbsolutePath() + File.separator + filePath;
    File javaFile = new File(fullPath);
    try {
      List<String> lines = Files.readLines(javaFile, Charset.defaultCharset());
      Optional<String> pkgOpt = lines.stream().filter(line -> line.startsWith("package"))
          .findFirst();
      String pkg;
      if (pkgOpt.isPresent()) {
        pkg = pkgOpt.get();
        pkg = pkg.substring("package".length() + 1, pkg.length() - 1);
      } else {
        pkg = "";
      }
      String className = filePath
          .substring(filePath.lastIndexOf(File.separator) + 1,
              filePath.length() - ".java".length());
      ClassUnderTest cut = ClassStore.get(pkg.isEmpty() ? className : pkg + "." + className);
      if (cut == null) {
        // test class
        return Collections.emptyList();
      }
      Set<Line> linesInCut = inst.getLines(cut);
      return candidates.stream()
          .filter(tc -> inst.getLinesCovered(tc).stream().anyMatch(l -> linesInCut.contains(l)))
          .collect(
              Collectors.toList());
    } catch (IOException e) {
      e.printStackTrace();
    }
    return Collections.emptyList();
  }

  @OptionProvider(paramKey = "schwa_secondary_objective")
  public static List<Comparator> getOptions() {
    ArrayList<Comparator> options = new ArrayList<>();
    options.add(new GreedyComparator());
    return options;
  }

  @Prerequisite(failureMessage = "Feature weights do not add up to 1. -Dschwa_revisions_weight, -Dschwa_authors_weight and -Dschwa_fixes_weight should sum to 1")
  public static boolean checkWeights() {
    return Util.doubleEquals(REVISIONS_WEIGHT + AUTHORS_WEIGHT + FIXES_WEIGHT, 1);
  }

  @Prerequisite(failureMessage = "Python3 is not installed on this system or is not executable on the system path. Please check your python3 installation.")
  public static boolean checkPythonInstallation() {
    int returnCode = runProcess("python3", "--version");
    return returnCode == 0;

  }

  @Prerequisite(failureMessage = "Schwa is not installed on this system, and Kanonizo failed to install it. Try again or visit Schwa on GitHub (https://github.com/andrefreitas/schwa) to manually install")
  public static boolean checkSchwaInstallation() {
    int returnCode = runProcess("schwa", "-h");
    if (returnCode != 0) {
      returnCode = runProcess("python3", "-m", "schwa", "-h");
    }
    //TODO install Schwa if not present on users system??
    return returnCode == 0;
  }

  @Prerequisite(failureMessage = "In order to use Schwa, project root must be set. The project root must be a git repository")
  public static boolean checkProjectRoot(){
    return Framework.getInstance().getRootFolder() != null;
  }

  private static int runProcess(String... process) {
    return runProcess(null, process);
  }

  private static int runProcess(File output, String... process) {
    ProcessBuilder pb = new ProcessBuilder(process);
    int returnCode = -1;
    try {
      if (output != null) {
        pb.redirectOutput(output);
      }
      Process processRun = pb.start();
      returnCode = processRun.waitFor();
    } catch (IOException e) {
      return returnCode;
    } catch (InterruptedException e) {
      return returnCode;
    }
    return returnCode;
  }
}
