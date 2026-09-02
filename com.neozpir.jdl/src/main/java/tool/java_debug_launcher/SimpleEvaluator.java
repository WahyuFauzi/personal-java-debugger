package tool.java_debug_launcher;

import java.util.ArrayList;
import java.util.List;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.InvocationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

public class SimpleEvaluator {
    private static final int INVOKE = ObjectReference.INVOKE_SINGLE_THREADED;

    private final VirtualMachine vm;
    private final ObjectReference thisObject;
    private final ThreadReference thread;
    private final int depth;

    private SimpleEvaluator(StackFrame frame, ObjectReference thisObject, ThreadReference thread, int depth) {
        this.thread = thread != null ? thread : (frame != null ? frame.thread() : null);
        this.vm = this.thread.virtualMachine();
        this.thisObject = thisObject;
        this.depth = depth;
    }

    public static Value evaluateInFrame(StackFrame frame, String expression) throws Exception {
        return new SimpleEvaluator(frame, null, frame.thread(), 0).run(expression);
    }

    public static Value evaluateOnObject(ObjectReference obj, ThreadReference thread, String expression)
            throws Exception {
        return new SimpleEvaluator(null, obj, thread, 0).run(expression);
    }

    private StackFrame currentFrame() throws Exception {
        if (thread == null) {
            throw new EvaluationException("no thread context");
        }
        return thread.frame(depth);
    }

    private ObjectReference hostThis() throws Exception {
        if (thisObject != null) {
            return thisObject;
        }
        try {
            return currentFrame().thisObject();
        } catch (Exception e) {
            return null;
        }
    }

    private Value run(String expression) throws Exception {
        Object result = new Parser(new Lexer(expression).tokenize()).parse().evalRaw();
        if (!(result instanceof Value)) {
            throw new EvaluationException("expression does not evaluate to a value");
        }
        return (Value) result;
    }

    private Value resolveLocal(String name) throws Exception {
        try {
            StackFrame f = currentFrame();
            for (LocalVariable local : f.visibleVariables()) {
                if (local.name().equals(name)) {
                    return f.getValue(local);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private Object resolveName(String name) throws Exception {
        Value local = resolveLocal(name);
        if (local != null) {
            return local;
        }
        ObjectReference host = hostThis();
        if (host != null) {
            Field field = findField(host.referenceType(), name);
            if (field != null) {
                return host.getValue(field);
            }
        }
        List<ReferenceType> classes = vm.classesByName(name);
        if (classes.isEmpty()) {
            classes = vm.classesByName("java.lang." + name);
        }
        if (!classes.isEmpty() && classes.get(0) instanceof ClassType) {
            return classes.get(0);
        }
        throw new EvaluationException("cannot resolve '" + name + "'");
    }

    private Field findField(ReferenceType type, String name) {
        for (Field field : type.allFields()) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private Value fieldOf(Object host, String name) throws Exception {
        if (host instanceof com.sun.jdi.ArrayReference) {
            if (name.equals("length")) {
                return vm.mirrorOf(((com.sun.jdi.ArrayReference) host).length());
            }
            throw new EvaluationException("no field '" + name + "' on array");
        }
        if (host instanceof ObjectReference) {
            Field field = findField(((ObjectReference) host).referenceType(), name);
            if (field != null) {
                return ((ObjectReference) host).getValue(field);
            }
            throw new EvaluationException("no field '" + name + "' on " + ((ObjectReference) host).type().name());
        }
        if (host instanceof ClassType) {
            Field field = findField((ClassType) host, name);
            if (field != null && field.isStatic()) {
                return ((ClassType) host).getValue(field);
            }
            throw new EvaluationException("no static field '" + name + "' on " + ((ClassType) host).name());
        }
        throw new EvaluationException("cannot access field '" + name + "' on non-object value");
    }

    private Value invoke(Object host, String name, List<Value> args) throws Exception {
        if (host instanceof ObjectReference) {
            Method method = findMethod(((ObjectReference) host).referenceType(), name, args);
            return ((ObjectReference) host).invokeMethod(thread, method, args, INVOKE);
        }
        if (host instanceof ClassType) {
            Method method = findMethod((ClassType) host, name, args);
            return ((ClassType) host).invokeMethod(thread, method, args, INVOKE);
        }
        throw new EvaluationException("cannot call method '" + name + "' on non-object value");
    }

    private Method findMethod(ReferenceType type, String name, List<Value> args) throws Exception {
        Method fallback = null;
        for (Method method : type.allMethods()) {
            if (!method.name().equals(name) || method.argumentTypeNames().size() != args.size()) {
                continue;
            }
            if (fallback == null) {
                fallback = method;
            }
            if (matches(method, args)) {
                return method;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new EvaluationException("no method '" + name + "' with " + args.size() + " arg(s) on " + type.name());
    }

    private boolean matches(Method method, List<Value> args) throws Exception {
        List<String> paramTypes = method.argumentTypeNames();
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            String param = paramTypes.get(i);
            if (arg == null) {
                continue;
            }
            if (arg instanceof PrimitiveValue) {
                String sig = primitiveName((PrimitiveValue) arg);
                if (sig.equals(param) || wrapperName(sig).equals(param)) {
                    continue;
                }
                return false;
            }
            if (!param.startsWith("java.lang.Object")
                    && !param.equals(((ObjectReference) arg).type().name())) {
                return false;
            }
        }
        return true;
    }

    private String primitiveName(PrimitiveValue value) {
        if (value instanceof BooleanValue) {
            return "boolean";
        }
        if (value instanceof com.sun.jdi.ByteValue) {
            return "byte";
        }
        if (value instanceof com.sun.jdi.CharValue) {
            return "char";
        }
        if (value instanceof com.sun.jdi.ShortValue) {
            return "short";
        }
        if (value instanceof com.sun.jdi.IntegerValue) {
            return "int";
        }
        if (value instanceof com.sun.jdi.LongValue) {
            return "long";
        }
        if (value instanceof com.sun.jdi.FloatValue) {
            return "float";
        }
        return "double";
    }

    private String wrapperName(String primitive) {
        switch (primitive) {
            case "boolean": return "java.lang.Boolean";
            case "byte": return "java.lang.Byte";
            case "char": return "java.lang.Character";
            case "short": return "java.lang.Short";
            case "int": return "java.lang.Integer";
            case "long": return "java.lang.Long";
            case "float": return "java.lang.Float";
            default: return "java.lang.Double";
        }
    }

    private boolean isString(Value value) {
        return value instanceof StringReference;
    }

    private boolean isNumeric(Value value) {
        return value instanceof PrimitiveValue && !(value instanceof BooleanValue);
    }

    private boolean isDouble(Value value) {
        return value instanceof com.sun.jdi.DoubleValue;
    }

    private boolean isFloat(Value value) {
        return value instanceof com.sun.jdi.FloatValue;
    }

    private boolean isLong(Value value) {
        return value instanceof com.sun.jdi.LongValue;
    }

    private double asDouble(Value value) {
        return ((PrimitiveValue) value).doubleValue();
    }

    private int asInt(Value value) {
        return ((PrimitiveValue) value).intValue();
    }

    private long asLong(Value value) {
        return ((PrimitiveValue) value).longValue();
    }

    private boolean asBoolean(Value value) throws Exception {
        if (!(value instanceof BooleanValue)) {
            throw new EvaluationException("expected a boolean value");
        }
        return ((BooleanValue) value).value();
    }

    private Value arith(String op, Value left, Value right) throws Exception {
        if (isString(left) || isString(right)) {
            return concat(left, right);
        }
        if (!isNumeric(left) || !isNumeric(right)) {
            throw new EvaluationException("operator '" + op + "' requires numeric operands");
        }
        if (isDouble(left) || isDouble(right)) {
            double a = asDouble(left);
            double b = asDouble(right);
            return vm.mirrorOf(computeDouble(op, a, b));
        }
        if (isFloat(left) || isFloat(right)) {
            float a = (float) asDouble(left);
            float b = (float) asDouble(right);
            return vm.mirrorOf(computeFloat(op, a, b));
        }
        if (isLong(left) || isLong(right)) {
            long a = asLong(left);
            long b = asLong(right);
            return vm.mirrorOf(computeLong(op, a, b));
        }
        int a = asInt(left);
        int b = asInt(right);
        return vm.mirrorOf(computeInt(op, a, b));
    }

    private double computeDouble(String op, double a, double b) throws Exception {
        switch (op) {
            case "+": return a + b;
            case "-": return a - b;
            case "*": return a * b;
            case "/": return a / b;
            case "%": return a % b;
            default: throw new EvaluationException("unknown operator " + op);
        }
    }

    private float computeFloat(String op, float a, float b) throws Exception {
        switch (op) {
            case "+": return a + b;
            case "-": return a - b;
            case "*": return a * b;
            case "/": return a / b;
            case "%": return a % b;
            default: throw new EvaluationException("unknown operator " + op);
        }
    }

    private long computeLong(String op, long a, long b) throws Exception {
        switch (op) {
            case "+": return a + b;
            case "-": return a - b;
            case "*": return a * b;
            case "/":
                if (b == 0) throw new EvaluationException("division by zero");
                return a / b;
            case "%":
                if (b == 0) throw new EvaluationException("division by zero");
                return a % b;
            default: throw new EvaluationException("unknown operator " + op);
        }
    }

    private int computeInt(String op, int a, int b) throws Exception {
        switch (op) {
            case "+": return a + b;
            case "-": return a - b;
            case "*": return a * b;
            case "/":
                if (b == 0) throw new EvaluationException("division by zero");
                return a / b;
            case "%":
                if (b == 0) throw new EvaluationException("division by zero");
                return a % b;
            default: throw new EvaluationException("unknown operator " + op);
        }
    }

    private Value concat(Value left, Value right) throws Exception {
        return concatStrings(stringOf(left), stringOf(right));
    }

    private Value concatStrings(StringReference first, StringReference second) throws Exception {
        Method concat = findMethod(first.referenceType(), "concat",
                List.of(second));
        return first.invokeMethod(thread, concat, List.of(second), INVOKE);
    }

    private StringReference stringOf(Value value) throws Exception {
        if (value instanceof StringReference) {
            return (StringReference) value;
        }
        return (StringReference) valueOfString(value);
    }

    private Value valueOfString(Value value) throws Exception {
        List<ReferenceType> classes = vm.classesByName("java.lang.String");
        if (classes.isEmpty() || !(classes.get(0) instanceof ClassType)) {
            throw new EvaluationException("java.lang.String not loaded");
        }
        ClassType stringClass = (ClassType) classes.get(0);
        for (Method method : stringClass.methodsByName("valueOf")) {
            if (method.argumentTypeNames().size() == 1 && matches(method, List.of(value))) {
                return stringClass.invokeMethod(thread, method, List.of(value), INVOKE);
            }
        }
        throw new EvaluationException("no String.valueOf overload for " + value);
    }

    private Value relational(String op, Value left, Value right) throws Exception {
        if (op.equals("==") || op.equals("!=")) {
            boolean eq;
            if (left == null || right == null) {
                eq = left == right;
            } else if (left instanceof PrimitiveValue && right instanceof PrimitiveValue) {
                if (isNumeric(left) && isNumeric(right)) {
                    eq = asDouble(left) == asDouble(right);
                } else {
                    eq = asBoolean(left) == asBoolean(right);
                }
            } else if (left instanceof ObjectReference && right instanceof ObjectReference) {
                eq = ((ObjectReference) left).uniqueID() == ((ObjectReference) right).uniqueID();
            } else {
                eq = false;
            }
            return vm.mirrorOf(op.equals("==") ? eq : !eq);
        }
        if (!isNumeric(left) || !isNumeric(right)) {
            throw new EvaluationException("operator '" + op + "' requires numeric operands");
        }
        double a = asDouble(left);
        double b = asDouble(right);
        boolean result;
        switch (op) {
            case "<": result = a < b; break;
            case "<=": result = a <= b; break;
            case ">": result = a > b; break;
            case ">=": result = a >= b; break;
            default: throw new EvaluationException("unknown operator " + op);
        }
        return vm.mirrorOf(result);
    }

    private static class EvaluationException extends Exception {
        EvaluationException(String message) {
            super(message);
        }
    }

    private enum Kind { IDENT, NUMBER, STRING, CHAR, OP, LPAREN, RPAREN, DOT, COMMA, EOF }

    private static class Token {
        final Kind kind;
        final String text;
        Token(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private static class Lexer {
        private final String input;
        private int pos;

        Lexer(String input) {
            this.input = input;
        }

        List<Token> tokenize() throws Exception {
            List<Token> tokens = new ArrayList<>();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (Character.isDigit(c)) {
                    tokens.add(new Token(Kind.NUMBER, readNumber()));
                } else if (Character.isLetter(c) || c == '_' || c == '$') {
                    tokens.add(new Token(Kind.IDENT, readIdent()));
                } else if (c == '"') {
                    tokens.add(new Token(Kind.STRING, readQuoted('"')));
                } else if (c == '\'') {
                    tokens.add(new Token(Kind.CHAR, readQuoted('\'')));
                } else if (c == '(') {
                    tokens.add(new Token(Kind.LPAREN, "("));
                    pos++;
                } else if (c == ')') {
                    tokens.add(new Token(Kind.RPAREN, ")"));
                    pos++;
                } else if (c == '.') {
                    tokens.add(new Token(Kind.DOT, "."));
                    pos++;
                } else if (c == ',') {
                    tokens.add(new Token(Kind.COMMA, ","));
                    pos++;
                } else {
                    tokens.add(new Token(Kind.OP, readOp()));
                }
            }
            tokens.add(new Token(Kind.EOF, ""));
            return tokens;
        }

        private String readIdent() {
            int start = pos;
            while (pos < input.length()
                    && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_' || input.charAt(pos) == '$')) {
                pos++;
            }
            return input.substring(start, pos);
        }

        private String readNumber() {
            int start = pos;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
            if (pos < input.length() && input.charAt(pos) == '.') {
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            String num = input.substring(start, pos);
            if (pos < input.length() && (input.charAt(pos) == 'l' || input.charAt(pos) == 'L')) {
                pos++;
            }
            return num;
        }

        private String readQuoted(char quote) throws Exception {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == quote) {
                    pos++;
                    return sb.toString();
                }
                if (c == '\\') {
                    pos++;
                    if (pos >= input.length()) {
                        break;
                    }
                    char esc = input.charAt(pos);
                    switch (esc) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '\\': sb.append('\\'); break;
                        case '"': sb.append('"'); break;
                        case '\'': sb.append('\''); break;
                        default: sb.append(esc);
                    }
                    pos++;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            throw new EvaluationException("unterminated string literal");
        }

        private String readOp() throws Exception {
            String two = pos + 1 < input.length() ? input.substring(pos, pos + 2) : "";
            if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                    || two.equals("<=") || two.equals(">=")) {
                pos += 2;
                return two;
            }
            char c = input.charAt(pos);
            if ("+-*/%<>!".indexOf(c) >= 0) {
                pos++;
                return String.valueOf(c);
            }
            throw new EvaluationException("unexpected character '" + c + "'");
        }
    }

    private interface Expr {
        Object evalRaw() throws Exception;
    }

    private class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private boolean matchOp(String op) {
            Token t = peek();
            if (t.kind == Kind.OP && t.text.equals(op)) {
                pos++;
                return true;
            }
            return false;
        }

        Expr parse() throws Exception {
            Expr expr = parseOr();
            if (peek().kind != Kind.EOF) {
                throw new EvaluationException("unexpected token '" + peek().text + "'");
            }
            return expr;
        }

        private Expr parseOr() throws Exception {
            Expr left = parseAnd();
            while (matchOp("||")) {
                Expr right = parseAnd();
                left = new OrExpr(left, right);
            }
            return left;
        }

        private Expr parseAnd() throws Exception {
            Expr left = parseEquality();
            while (matchOp("&&")) {
                Expr right = parseEquality();
                left = new AndExpr(left, right);
            }
            return left;
        }

        private Expr parseEquality() throws Exception {
            Expr left = parseRelational();
            while (true) {
                if (matchOp("==")) {
                    left = new BinaryExpr(left, "==", parseRelational());
                } else if (matchOp("!=")) {
                    left = new BinaryExpr(left, "!=", parseRelational());
                } else {
                    return left;
                }
            }
        }

        private Expr parseRelational() throws Exception {
            Expr left = parseAdditive();
            while (true) {
                if (matchOp("<")) {
                    left = new BinaryExpr(left, "<", parseAdditive());
                } else if (matchOp("<=")) {
                    left = new BinaryExpr(left, "<=", parseAdditive());
                } else if (matchOp(">")) {
                    left = new BinaryExpr(left, ">", parseAdditive());
                } else if (matchOp(">=")) {
                    left = new BinaryExpr(left, ">=", parseAdditive());
                } else {
                    return left;
                }
            }
        }

        private Expr parseAdditive() throws Exception {
            Expr left = parseMultiplicative();
            while (true) {
                if (matchOp("+")) {
                    left = new BinaryExpr(left, "+", parseMultiplicative());
                } else if (matchOp("-")) {
                    left = new BinaryExpr(left, "-", parseMultiplicative());
                } else {
                    return left;
                }
            }
        }

        private Expr parseMultiplicative() throws Exception {
            Expr left = parseUnary();
            while (true) {
                if (matchOp("*")) {
                    left = new BinaryExpr(left, "*", parseUnary());
                } else if (matchOp("/")) {
                    left = new BinaryExpr(left, "/", parseUnary());
                } else if (matchOp("%")) {
                    left = new BinaryExpr(left, "%", parseUnary());
                } else {
                    return left;
                }
            }
        }

        private Expr parseUnary() throws Exception {
            if (matchOp("!")) {
                return new NotExpr(parseUnary());
            }
            if (matchOp("-")) {
                return new NegateExpr(parseUnary());
            }
            return parsePostfix();
        }

        private Expr parsePostfix() throws Exception {
            Expr expr = parsePrimary();
            while (true) {
                if (peek().kind == Kind.DOT) {
                    next();
                    Token name = next();
                    if (name.kind != Kind.IDENT) {
                        throw new EvaluationException("expected identifier after '.'");
                    }
                    if (peek().kind == Kind.LPAREN) {
                        next();
                        List<Expr> args = parseArgs();
                        expr = new CallExpr(expr, name.text, args);
                    } else {
                        expr = new FieldExpr(expr, name.text);
                    }
                } else if (peek().kind == Kind.LPAREN) {
                    next();
                    List<Expr> args = parseArgs();
                    expr = new CallExpr(expr, null, args);
                } else {
                    return expr;
                }
            }
        }

        private List<Expr> parseArgs() throws Exception {
            List<Expr> args = new ArrayList<>();
            if (peek().kind == Kind.RPAREN) {
                next();
                return args;
            }
            args.add(parseOr());
            while (peek().kind == Kind.COMMA) {
                next();
                args.add(parseOr());
            }
            if (peek().kind != Kind.RPAREN) {
                throw new EvaluationException("expected ')' in argument list");
            }
            next();
            return args;
        }

        private Expr parsePrimary() throws Exception {
            Token token = next();
            switch (token.kind) {
                case NUMBER:
                    return new LiteralExpr(parseNumber(token.text));
                case STRING:
                    return new LiteralExpr(vm.mirrorOf(token.text));
                case CHAR:
                    if (token.text.length() == 1) {
                        return new LiteralExpr(vm.mirrorOf(token.text.charAt(0)));
                    }
                    throw new EvaluationException("invalid char literal '" + token.text + "'");
                case IDENT:
                    if (token.text.equals("true")) {
                        return new LiteralExpr(vm.mirrorOf(true));
                    }
                    if (token.text.equals("false")) {
                        return new LiteralExpr(vm.mirrorOf(false));
                    }
                    if (token.text.equals("null")) {
                        return new LiteralExpr(null);
                    }
                    if (token.text.equals("this")) {
                        ObjectReference host = hostThis();
                        if (host == null) {
                            throw new EvaluationException("'this' is not available");
                        }
                        return new LiteralExpr(host);
                    }
                    return new NameExpr(token.text);
                case LPAREN: {
                    Expr inner = parseOr();
                    if (peek().kind != Kind.RPAREN) {
                        throw new EvaluationException("expected ')'");
                    }
                    next();
                    return inner;
                }
                default:
                    throw new EvaluationException("unexpected token '" + token.text + "'");
            }
        }

        private Value parseNumber(String text) throws Exception {
            boolean longSuffix = text.endsWith("l") || text.endsWith("L");
            String num = longSuffix ? text.substring(0, text.length() - 1) : text;
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                return vm.mirrorOf(Double.parseDouble(num));
            }
            if (longSuffix) {
                return vm.mirrorOf(Long.parseLong(num));
            }
            return vm.mirrorOf(Integer.parseInt(num));
        }
    }

    private class LiteralExpr implements Expr {
        private final Value value;

        LiteralExpr(Value value) {
            this.value = value;
        }

        @Override
        public Object evalRaw() {
            return value;
        }
    }

    private class NameExpr implements Expr {
        private final String name;

        NameExpr(String name) {
            this.name = name;
        }

        @Override
        public Object evalRaw() throws Exception {
            return resolveName(name);
        }
    }

    private class FieldExpr implements Expr {
        private final Expr target;
        private final String name;

        FieldExpr(Expr target, String name) {
            this.target = target;
            this.name = name;
        }

        @Override
        public Object evalRaw() throws Exception {
            return fieldOf(target.evalRaw(), name);
        }
    }

    private class CallExpr implements Expr {
        private final Expr target;
        private final String name;
        private final List<Expr> args;

        CallExpr(Expr target, String name, List<Expr> args) {
            this.target = target;
            this.name = name;
            this.args = args;
        }

        @Override
        public Object evalRaw() throws Exception {
            List<Value> values = new ArrayList<>();
            for (Expr arg : args) {
                Object raw = arg.evalRaw();
                if (!(raw instanceof Value)) {
                    throw new EvaluationException("method arguments must be values");
                }
                values.add((Value) raw);
            }
            Object host;
            String methodName = name;
            if (target == null) {
                host = receiver();
            } else {
                try {
                    host = target.evalRaw();
                } catch (EvaluationException e) {
                    if (target instanceof NameExpr && name == null) {
                        methodName = ((NameExpr) target).name;
                        host = receiver();
                    } else {
                        throw e;
                    }
                }
            }
            if (host == null) {
                throw new EvaluationException("cannot call method on null");
            }
            return invoke(host, methodName, values);
        }

        private Object receiver() throws Exception {
            ObjectReference host = hostThis();
            if (host == null) {
                throw new EvaluationException("no receiver for method call");
            }
            return host;
        }
    }

    private class BinaryExpr implements Expr {
        private final Expr left;
        private final String op;
        private final Expr right;

        BinaryExpr(Expr left, String op, Expr right) {
            this.left = left;
            this.op = op;
            this.right = right;
        }

        @Override
        public Object evalRaw() throws Exception {
            Object l = left.evalRaw();
            Object r = right.evalRaw();
            if (!(l instanceof Value) || !(r instanceof Value)) {
                throw new EvaluationException("operator '" + op + "' requires values");
            }
            if (op.equals("+") || op.equals("-") || op.equals("*") || op.equals("/") || op.equals("%")) {
                return arith(op, (Value) l, (Value) r);
            }
            return relational(op, (Value) l, (Value) r);
        }
    }

    private class AndExpr implements Expr {
        private final Expr left;
        private final Expr right;

        AndExpr(Expr left, Expr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public Object evalRaw() throws Exception {
            Value l = (Value) left.evalRaw();
            if (!asBoolean(l)) {
                return vm.mirrorOf(false);
            }
            return vm.mirrorOf(asBoolean((Value) right.evalRaw()));
        }
    }

    private class OrExpr implements Expr {
        private final Expr left;
        private final Expr right;

        OrExpr(Expr left, Expr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public Object evalRaw() throws Exception {
            Value l = (Value) left.evalRaw();
            if (asBoolean(l)) {
                return vm.mirrorOf(true);
            }
            return vm.mirrorOf(asBoolean((Value) right.evalRaw()));
        }
    }

    private class NotExpr implements Expr {
        private final Expr inner;

        NotExpr(Expr inner) {
            this.inner = inner;
        }

        @Override
        public Object evalRaw() throws Exception {
            return vm.mirrorOf(!asBoolean((Value) inner.evalRaw()));
        }
    }

    private class NegateExpr implements Expr {
        private final Expr inner;

        NegateExpr(Expr inner) {
            this.inner = inner;
        }

        @Override
        public Object evalRaw() throws Exception {
            Value value = (Value) inner.evalRaw();
            if (!isNumeric(value)) {
                throw new EvaluationException("unary '-' requires a numeric operand");
            }
            return arith("-", vm.mirrorOf(0), value);
        }
    }
}
