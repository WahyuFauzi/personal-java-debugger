package tool.java_debug_launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.Location;
import com.sun.jdi.LongValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;

/**
 * Unit tests for {@link SimpleEvaluator}. The evaluator operates on JDI values, so each
 * test launches a debuggee, runs it to a breakpoint, and evaluates in that suspended frame.
 * A fresh debuggee per test keeps the suspended frame valid for the whole test body.
 */
class SimpleEvaluatorTest {

    private static final String FIXTURE_RESOURCE = "/fixtures/eval/EvalTarget.java";
    private static final String TARGET_CLASS = "eval.EvalTarget";
    /** Executable line inside {@code run(...)} where all locals are visible. */
    private static final int BREAKPOINT_LINE = 30;

    private static Path classesDir;

    private VirtualMachine vm;
    private BreakpointEvent breakpointEvent;
    @SuppressWarnings("unused")
    private static EventSet suspendedEventSet;

    @BeforeAll
    static void compileFixture() throws IOException {
        Path workDir = Files.createTempDirectory("simple-evaluator-test");
        classesDir = compile(FIXTURE_RESOURCE, workDir.resolve("src/eval/EvalTarget.java"));
    }

    @BeforeEach
    void launchDebuggee() throws Exception {
        vm = launchSuspended(classesDir);
        breakpointEvent = runToBreakpoint(vm, TARGET_CLASS, BREAKPOINT_LINE);
        assertNotNull(breakpointEvent, "did not reach the breakpoint in " + TARGET_CLASS);
    }

    @AfterEach
    void shutdownDebuggee() {
        if (vm != null) {
            try {
                vm.dispose();
            } catch (Exception ignored) {
                // best-effort teardown
            }
        }
    }

    private StackFrame frame() throws Exception {
        return breakpointEvent.thread().frame(0);
    }

    // ---------------------------------------------------------------- arithmetic

    @Test
    void arithmeticOnInts() throws Exception {
        assertInt(14, "a + b");
        assertInt(6, "a - b");
        assertInt(40, "a * b");
        assertInt(2, "a / b");
        assertInt(2, "a % b");
        assertInt(-10, "-a");
    }

    @Test
    void arithmeticRespectsPrecedenceAndParentheses() throws Exception {
        assertInt(18, "a + b * 2");
        assertInt(28, "(a + b) * 2");
        assertInt(84, "(a + b) * (a - b)");
    }

    @Test
    void arithmeticOnFloatingPoint() throws Exception {
        assertDouble(5.0, "d * 2.0");
        assertDouble(1.25, "d / 2");
        assertDouble(12.5, "a + d");
    }

    @Test
    void arithmeticOnLongsWidens() throws Exception {
        assertLong(9000000001L, "big + 1");
        assertLong(18000000000L, "big * 2");
    }

    @Test
    void divisionByZeroFails() {
        assertFails("a / 0");
        assertFails("a % 0");
    }

    // ------------------------------------------------------------- comparisons

    @Test
    void relationalOperators() throws Exception {
        assertBool(true, "a > b");
        assertBool(false, "a < b");
        assertBool(true, "a >= 10");
        assertBool(false, "a <= 9");
        assertBool(true, "a == 10");
        assertBool(false, "b != 4");
    }

    @Test
    void logicalOperators() throws Exception {
        assertBool(true, "flag && true");
        assertBool(true, "flag || false");
        assertBool(false, "!flag");
        assertBool(true, "a > b && b > 0");
        assertBool(false, "a < b || a < 0");
    }

    // ----------------------------------------------- strings + type coercion

    @Test
    void stringConcatenationAndCoercion() throws Exception {
        assertString("hello world", "text + \" world\"");
        assertString("a1", "\"a\" + 1");
        assertString("nhello", "label + text");
        assertString("3x", "1 + 2 + \"x\"");
        assertString("2.5:10", "d + \":\" + a");
    }

    // ------------------------------------------------------ literals

    @Test
    void literals() throws Exception {
        assertInt(42, "42");
        assertDouble(3.14, "3.14");
        assertString("x", "\"x\"");
        assertEquals('c', ((CharValue) SimpleEvaluator.evaluateInFrame(frame(), "'c'")).value());
        assertBool(true, "true");
        assertBool(false, "false");
    }

    // ------------------------------------------------- variable resolution

    @Test
    void resolvesLocalsFieldsAndParameters() throws Exception {
        assertInt(10, "a");            // parameter
        assertInt(14, "local");        // local
        assertInt(3, "count");         // instance field
        assertString("n", "label");    // instance field
        assertInt(7, "STATIC");        // static field
        assertInt(3, "this.count");    // explicit receiver
    }

    @Test
    void unknownNameFails() {
        assertFails("noSuchVariable");
    }

    // ------------------------------------------------- method invocation

    @Test
    void methodCallsOnLiteralsAndLocals() throws Exception {
        assertInt(3, "\"abc\".length()");
        assertInt(5, "text.length()");
        assertString("HELLO", "text.toUpperCase()");
        assertString("ello", "text.substring(1)");
        assertString("hello!", "text.concat(\"!\")");
    }

    @Test
    void methodCallsOnThisAndStaticTypes() throws Exception {
        assertInt(3, "add(1, 2)");
        assertString("hello!", "shout(text)");
        assertString("10", "String.valueOf(a)");
    }

    // ---------------------------------------------------------- edge cases

    @Test
    void malformedExpressionsFail() {
        assertFails("");
        assertFails("   ");
        assertFails("a +");
        assertFails("(a");
        assertFails("a b");
        assertFails("a.");
        assertFails("\"unterminated");
        assertFails("@");
    }

    @Test
    void nullLiteralDoesNotEvaluateToAValue() {
        // `null` parses, but the evaluator requires an expression to produce a JDI Value.
        assertFails("null");
    }

    // ------------------------------------------------------------- helpers

    private void assertInt(int expected, String expression) throws Exception {
        Value value = SimpleEvaluator.evaluateInFrame(frame(), expression);
        assertInstanceOf(IntegerValue.class, value, expression);
        assertEquals(expected, ((IntegerValue) value).value(), expression);
    }

    private void assertLong(long expected, String expression) throws Exception {
        Value value = SimpleEvaluator.evaluateInFrame(frame(), expression);
        assertInstanceOf(LongValue.class, value, expression);
        assertEquals(expected, ((LongValue) value).value(), expression);
    }

    private void assertDouble(double expected, String expression) throws Exception {
        Value value = SimpleEvaluator.evaluateInFrame(frame(), expression);
        assertInstanceOf(DoubleValue.class, value, expression);
        assertEquals(expected, ((DoubleValue) value).value(), 1e-9, expression);
    }

    private void assertBool(boolean expected, String expression) throws Exception {
        Value value = SimpleEvaluator.evaluateInFrame(frame(), expression);
        assertInstanceOf(BooleanValue.class, value, expression);
        assertEquals(expected, ((BooleanValue) value).value(), expression);
    }

    private void assertString(String expected, String expression) throws Exception {
        Value value = SimpleEvaluator.evaluateInFrame(frame(), expression);
        assertInstanceOf(StringReference.class, value, expression);
        assertEquals(expected, ((StringReference) value).value(), expression);
    }

    private void assertFails(String expression) {
        assertThrows(Exception.class, () -> SimpleEvaluator.evaluateInFrame(frame(), expression),
                "expected evaluation of '" + expression + "' to fail");
    }

    // -------------------------------------------------------- JDI harness

    private static Path compile(String resource, Path sourceFile) throws IOException {
        Files.createDirectories(sourceFile.getParent());
        try (InputStream in = SimpleEvaluatorTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "fixture resource not found on classpath: " + resource);
            Files.copy(in, sourceFile);
        }

        Path outputDir = sourceFile.getParent().getParent().getParent().resolve("classes");
        Files.createDirectories(outputDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "no system Java compiler available; run the build on a JDK");
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromFiles(List.of(sourceFile.toFile()));
            List<String> options = List.of("-g", "-d", outputDir.toString());
            boolean compiled = compiler.getTask(null, fileManager, null, options, null, units).call();
            assertTrue(compiled, "failed to compile the fixture: " + sourceFile);
        }
        return outputDir;
    }

    private static VirtualMachine launchSuspended(Path classesDir) throws Exception {
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("main").setValue(TARGET_CLASS);
        arguments.get("options").setValue("-cp " + classesDir);
        arguments.get("suspend").setValue("true");
        return connector.launch(arguments);
    }

    /** Enables a class-prepare request, installs a breakpoint when the class loads, and runs to it. */
    private static BreakpointEvent runToBreakpoint(VirtualMachine vm, String className, int line) throws Exception {
        EventRequestManager requestManager = vm.eventRequestManager();
        ClassPrepareRequest classPrepare = requestManager.createClassPrepareRequest();
        classPrepare.addClassFilter(className);
        classPrepare.enable();

        vm.resume();

        EventQueue queue = vm.eventQueue();
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            EventSet eventSet = queue.remove(1_000);
            if (eventSet == null) {
                continue;
            }

            BreakpointEvent hit = null;
            EventIterator iterator = eventSet.eventIterator();
            while (iterator.hasNext()) {
                Event event = iterator.next();
                if (event instanceof ClassPrepareEvent) {
                    ReferenceType type = ((ClassPrepareEvent) event).referenceType();
                    for (Location location : type.locationsOfLine(line)) {
                        BreakpointRequest breakpoint = requestManager.createBreakpointRequest(location);
                        breakpoint.enable();
                    }
                } else if (event instanceof BreakpointEvent) {
                    hit = (BreakpointEvent) event;
                }
            }

            if (hit != null) {
                // Leave the event set suspended: the debuggee thread stays at the breakpoint,
                // which is exactly the state evaluateInFrame() needs. Hold a reference so the
                // set cannot be collected while the test body runs.
                suspendedEventSet = eventSet;
                return hit;
            }
            eventSet.resume();
        }
        throw new IllegalStateException("timed out waiting for a breakpoint in " + className + ":" + line);
    }
}
