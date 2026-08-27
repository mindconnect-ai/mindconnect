package ai.mindconnect.script.mini;

import javax.script.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.*;

/**
 * MiniScript — a zero-dependency JSR-223 {@link AbstractScriptEngine}.
 *
 * <p>Obtain an instance via {@link MiniScriptEngineFactory#getScriptEngine()} or
 * through the standard {@link ScriptEngineManager} discovery (when the
 * {@code META-INF/services} entry is present on the classpath).
 *
 * Syntax overview:
 * ─────────────────────────────────────────────────────
 *   // comment
 *
 *   // primitives
 *   x    = 42
 *   name = "hello"
 *   flag = true
 *
 *   // list literal  (produces java.util.ArrayList)
 *   ids  = ["a", "b", "c"]
 *   nums = [1, 2, x + 1]
 *
 *   // map literal   (produces java.util.LinkedHashMap)
 *   person = {id: "1", name: "David", age: 40}
 *   mixed  = {"key-with-dash": 99, nested: {x: 1}}
 *
 *   // index access
 *   first  = ids[0]
 *   last   = ids[-1]             // negative indexing on lists
 *   third  = nums[x - 1]
 *   david  = person["name"]      // string key
 *   david2 = person.name         // dot-access on map (same as above)
 *
 *   // method calls on java objects
 *   result = obj.doSomething(x, "arg")
 *   size   = ids.size()
 *   prop   = obj.myProp          // calls getMyProp() / isMyProp() automatically
 *
 *   // if / else if / else
 *   if (x > 10) {
 *       obj.big()
 *   } else if (x == 5) {
 *       obj.medium()
 *   } else {
 *       obj.small()
 *   }
 *
 *   // for-each loop  (works on List, array, Map, String)
 *   for (item in items) {
 *       println(item)
 *   }
 *
 *   // for-each with index
 *   for (item, i in items) {
 *       println(i + ": " + item)
 *   }
 *
 *   // result of a for loop is a List of the last expression of each iteration
 *   doubled = for (n in nums) { n * 2 }
 *
 * Operators: ==  !=  <  >  <=  >=  &&  ||  !  +  -  *  /
 * Built-ins: print(...)  println(...)  str(x)  num(x)  bool(x)  len(x)
 */
public class    MiniScriptEngine extends AbstractScriptEngine {

    private final MiniScriptEngineFactory factory;

    MiniScriptEngine(MiniScriptEngineFactory factory) {
        this.factory = factory;
    }

    // -----------------------------------------------------------------------
    // AbstractScriptEngine
    // -----------------------------------------------------------------------

    @Override
    public Object eval(String script, ScriptContext ctx) throws ScriptException {
        try {
            List<Token> tokens = new Lexer(script).tokenize();
            Node ast = new Parser(tokens).parse();
            return new Evaluator(ctx).eval(ast);
        } catch (ScriptException e) {
            throw e;
        } catch (Exception e) {
            throw new ScriptException(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        }
    }

    @Override
    public Object eval(Reader reader, ScriptContext ctx) throws ScriptException {
        try (BufferedReader br = new BufferedReader(reader)) {
            return eval(br.lines().collect(Collectors.joining("\n")), ctx);
        } catch (ScriptException e) {
            throw e;
        } catch (IOException e) {
            throw new ScriptException(e);
        }
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LEXER
    // ─────────────────────────────────────────────────────────────────────────

    enum TT {
        NUM, STR, BOOL, NULL, IDENT,
        ASSIGN, EQ, NEQ, LT, GT, LTE, GTE, AND, OR, NOT,
        PLUS, MINUS, MUL, DIV,
        LPAREN, RPAREN,
        LBRACE, RBRACE,
        LBRACKET, RBRACKET,
        COMMA, DOT, COLON,
        IF, ELSE, FOR, IN, EOF
    }

    record Token(TT type, String value, int line) {
        @Override public String toString() { return type + "(" + value + ")@" + line; }
    }

    static class Lexer {
        private final String src;
        private int pos = 0, line = 1;

        Lexer(String src) { this.src = src; }

        List<Token> tokenize() throws ScriptException {
            List<Token> tokens = new ArrayList<>();
            while (pos < src.length()) {
                skipWhitespaceAndComments();
                if (pos >= src.length()) break;
                char c = src.charAt(pos);
                if (c == '\n') { line++; pos++; continue; }
                if (c == '\r') { pos++; continue; }
                Token t = nextToken();
                if (t != null) tokens.add(t);
            }
            tokens.add(new Token(TT.EOF, "", line));
            return tokens;
        }

        private void skipWhitespaceAndComments() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r') {
                    pos++;
                } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                    while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                } else {
                    break;
                }
            }
        }

        private Token nextToken() throws ScriptException {
            char c = src.charAt(pos);
            int sl = line;

            if (Character.isDigit(c)) return readNumber(sl);
            if (c == '"' || c == '\'') return readString(c, sl);
            if (Character.isLetter(c) || c == '_') return readIdent(sl);

            pos++;
            return switch (c) {
                case '=' -> pos < src.length() && src.charAt(pos) == '=' ?
                        advance(new Token(TT.EQ,     "==", sl)) : new Token(TT.ASSIGN, "=",  sl);
                case '!' -> pos < src.length() && src.charAt(pos) == '=' ?
                        advance(new Token(TT.NEQ,    "!=", sl)) : new Token(TT.NOT,    "!",  sl);
                case '<' -> pos < src.length() && src.charAt(pos) == '=' ?
                        advance(new Token(TT.LTE,    "<=", sl)) : new Token(TT.LT,     "<",  sl);
                case '>' -> pos < src.length() && src.charAt(pos) == '=' ?
                        advance(new Token(TT.GTE,    ">=", sl)) : new Token(TT.GT,     ">",  sl);
                case '&' -> {
                    if (pos < src.length() && src.charAt(pos) == '&')
                        yield advance(new Token(TT.AND, "&&", sl));
                    throw new ScriptException("Expected '&&'", null, sl);
                }
                case '|' -> {
                    if (pos < src.length() && src.charAt(pos) == '|')
                        yield advance(new Token(TT.OR, "||", sl));
                    throw new ScriptException("Expected '||'", null, sl);
                }
                case '+' -> new Token(TT.PLUS,     "+",  sl);
                case '-' -> new Token(TT.MINUS,    "-",  sl);
                case '*' -> new Token(TT.MUL,      "*",  sl);
                case '/' -> new Token(TT.DIV,      "/",  sl);
                case '(' -> new Token(TT.LPAREN,   "(",  sl);
                case ')' -> new Token(TT.RPAREN,   ")",  sl);
                case '{' -> new Token(TT.LBRACE,   "{",  sl);
                case '}' -> new Token(TT.RBRACE,   "}",  sl);
                case '[' -> new Token(TT.LBRACKET, "[",  sl);
                case ']' -> new Token(TT.RBRACKET, "]",  sl);
                case ',' -> new Token(TT.COMMA,    ",",  sl);
                case '.' -> new Token(TT.DOT,      ".",  sl);
                case ':' -> new Token(TT.COLON,    ":",  sl);
                case ';' -> null; // optional statement terminator – silently skip
                default  -> throw new ScriptException("Unexpected character: '" + c + "'", null, sl);
            };
        }

        private Token advance(Token t) { pos++; return t; }

        private Token readNumber(int sl) {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            return new Token(TT.NUM, src.substring(start, pos), sl);
        }

        private Token readString(char quote, int sl) throws ScriptException {
            pos++; // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != quote) {
                char c = src.charAt(pos++);
                if (c == '\\' && pos < src.length()) {
                    sb.append(switch (src.charAt(pos++)) {
                        case 'n'  -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
                        case '"'  -> '"';  case '\'' -> '\''; case '\\' -> '\\';
                        default   -> src.charAt(pos - 1);
                    });
                } else {
                    sb.append(c);
                }
            }
            if (pos >= src.length()) throw new ScriptException("Unterminated string literal", null, sl);
            pos++; // skip closing quote
            return new Token(TT.STR, sb.toString(), sl);
        }

        private Token readIdent(int sl) {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
            String word = src.substring(start, pos);
            return switch (word) {
                case "if"    -> new Token(TT.IF,   word, sl);
                case "else"  -> new Token(TT.ELSE, word, sl);
                case "for"   -> new Token(TT.FOR,  word, sl);
                case "in"    -> new Token(TT.IN,   word, sl);
                case "true", "false" -> new Token(TT.BOOL, word, sl);
                case "null"  -> new Token(TT.NULL, word, sl);
                default      -> new Token(TT.IDENT, word, sl);
            };
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AST NODES
    // ─────────────────────────────────────────────────────────────────────────

    sealed interface Node permits
            BlockNode, AssignNode, IfNode, ForEachNode, ExprStmtNode,
            MethodCallNode, IndexNode,
            ListLiteralNode, MapLiteralNode,
            BinaryOpNode, UnaryOpNode,
            LiteralNode, IdentNode {}

    record BlockNode(List<Node> stmts) implements Node {}
    record AssignNode(String name, Node value) implements Node {}

    record IfNode(List<IfNode.Branch> branches, Node elseBranch) implements Node {
        record Branch(Node condition, Node body) {}
    }

    /**
     * for (item in collection) { ... }
     * for (item, i in collection) { ... }
     * indexVar is null when no index variable is declared.
     */
    record ForEachNode(String itemVar, String indexVar, Node collection, Node body) implements Node {}

    record ExprStmtNode(Node expr) implements Node {}

    /** target == null → top-level built-in call */
    record MethodCallNode(Node target, String method, List<Node> args) implements Node {}

    /** target[index] – works for List, Map, and Java arrays */
    record IndexNode(Node target, Node index) implements Node {}

    /** ["a", "b", "c"]  →  java.util.ArrayList */
    record ListLiteralNode(List<Node> elements) implements Node {}

    /** {key: expr, "other-key": expr}  →  java.util.LinkedHashMap */
    record MapLiteralNode(List<Map.Entry<String, Node>> entries) implements Node {}

    record BinaryOpNode(String op, Node left, Node right) implements Node {}
    record UnaryOpNode(String op, Node operand) implements Node {}
    record LiteralNode(Object value) implements Node {}
    record IdentNode(String name) implements Node {}

    // ─────────────────────────────────────────────────────────────────────────
    // PARSER
    // ─────────────────────────────────────────────────────────────────────────

    static class Parser {
        private final List<Token> tokens;
        private int pos = 0;

        Parser(List<Token> tokens) { this.tokens = tokens; }

        Node parse() throws ScriptException {
            List<Node> stmts = new ArrayList<>();
            while (!check(TT.EOF)) {
                Node s = parseStatement();
                if (s != null) stmts.add(s);
            }
            return new BlockNode(stmts);
        }

        private Token peek()               { return tokens.get(pos); }
        private Token peek(int n)          { return tokens.get(Math.min(pos + n, tokens.size() - 1)); }
        private Token consume()            { return tokens.get(pos++); }
        private boolean check(TT t)        { return peek().type() == t; }
        private boolean checkAt(int n, TT t) { return peek(n).type() == t; }

        private Token expect(TT type) throws ScriptException {
            if (!check(type))
                throw new ScriptException(
                        "Expected " + type + " but found '" + peek().value() + "'",
                        null, peek().line());
            return consume();
        }

        private Node parseStatement() throws ScriptException {
            if (check(TT.IF))  return parseIf();
            if (check(TT.FOR)) return parseForEach();

            if (check(TT.IDENT) && checkAt(1, TT.ASSIGN)) {
                String name = consume().value();
                consume(); // =
                return new AssignNode(name, parseExpression());
            }

            return new ExprStmtNode(parseExpression());
        }

        private ForEachNode parseForEach() throws ScriptException {
            expect(TT.FOR);
            expect(TT.LPAREN);
            String itemVar = expect(TT.IDENT).value();
            String indexVar = null;
            if (check(TT.COMMA)) {
                consume();
                indexVar = expect(TT.IDENT).value();
            }
            expect(TT.IN);
            Node collection = parseExpression();
            expect(TT.RPAREN);
            Node body = parseBlock();
            return new ForEachNode(itemVar, indexVar, collection, body);
        }

        private IfNode parseIf() throws ScriptException {
            expect(TT.IF);
            List<IfNode.Branch> branches = new ArrayList<>();
            branches.add(new IfNode.Branch(parseParenExpr(), parseBlock()));

            Node elseBranch = null;
            while (check(TT.ELSE)) {
                consume();
                if (check(TT.IF)) { consume(); branches.add(new IfNode.Branch(parseParenExpr(), parseBlock())); }
                else              { elseBranch = parseBlock(); break; }
            }
            return new IfNode(branches, elseBranch);
        }

        private Node parseParenExpr() throws ScriptException {
            expect(TT.LPAREN); Node e = parseExpression(); expect(TT.RPAREN); return e;
        }

        // Explicit block parser — only called from if/else, never from parsePrimary.
        // Keeps { unambiguous: LBRACE in expression context = map literal.
        private Node parseBlock() throws ScriptException {
            expect(TT.LBRACE);
            List<Node> stmts = new ArrayList<>();
            while (!check(TT.RBRACE) && !check(TT.EOF)) {
                Node s = parseStatement();
                if (s != null) stmts.add(s);
            }
            expect(TT.RBRACE);
            return new BlockNode(stmts);
        }

        private Node parseExpression()     throws ScriptException { return parseOr(); }

        private Node parseOr()             throws ScriptException {
            Node n = parseAnd();
            while (check(TT.OR))  { consume(); n = new BinaryOpNode("||", n, parseAnd()); }
            return n;
        }

        private Node parseAnd()            throws ScriptException {
            Node n = parseEquality();
            while (check(TT.AND)) { consume(); n = new BinaryOpNode("&&", n, parseEquality()); }
            return n;
        }

        private Node parseEquality()       throws ScriptException {
            Node n = parseComparison();
            while (check(TT.EQ) || check(TT.NEQ))
                n = new BinaryOpNode(consume().value(), n, parseComparison());
            return n;
        }

        private Node parseComparison()     throws ScriptException {
            Node n = parseAdditive();
            while (check(TT.LT) || check(TT.GT) || check(TT.LTE) || check(TT.GTE))
                n = new BinaryOpNode(consume().value(), n, parseAdditive());
            return n;
        }

        private Node parseAdditive()       throws ScriptException {
            Node n = parseMultiplicative();
            while (check(TT.PLUS) || check(TT.MINUS))
                n = new BinaryOpNode(consume().value(), n, parseMultiplicative());
            return n;
        }

        private Node parseMultiplicative() throws ScriptException {
            Node n = parseUnary();
            while (check(TT.MUL) || check(TT.DIV))
                n = new BinaryOpNode(consume().value(), n, parseUnary());
            return n;
        }

        private Node parseUnary()          throws ScriptException {
            if (check(TT.NOT))   { consume(); return new UnaryOpNode("!", parseUnary()); }
            if (check(TT.MINUS)) { consume(); return new UnaryOpNode("-", parseUnary()); }
            return parsePostfix();
        }

        private Node parsePostfix() throws ScriptException {
            Node node = parsePrimary();
            outer:
            while (true) {
                if (check(TT.DOT)) {
                    consume();
                    String member = expect(TT.IDENT).value();
                    if (check(TT.LPAREN)) {
                        consume();
                        List<Node> args = parseArgList();
                        expect(TT.RPAREN);
                        node = new MethodCallNode(node, member, args);
                    } else {
                        node = new MethodCallNode(node, member, List.of());
                    }
                } else if (check(TT.LBRACKET)) {
                    consume();
                    Node index = parseExpression();
                    expect(TT.RBRACKET);
                    node = new IndexNode(node, index);
                } else {
                    break outer;
                }
            }
            return node;
        }

        private List<Node> parseArgList() throws ScriptException {
            List<Node> args = new ArrayList<>();
            if (!check(TT.RPAREN)) {
                args.add(parseExpression());
                while (check(TT.COMMA)) { consume(); args.add(parseExpression()); }
            }
            return args;
        }

        private Node parsePrimary() throws ScriptException {
            Token t = peek();
            return switch (t.type()) {
                case NUM  -> { consume(); yield new LiteralNode(parseNumber(t.value())); }
                case STR  -> { consume(); yield new LiteralNode(t.value()); }
                case BOOL -> { consume(); yield new LiteralNode(Boolean.parseBoolean(t.value())); }
                case NULL -> { consume(); yield new LiteralNode(null); }

                case IDENT -> {
                    consume();
                    if (check(TT.LPAREN)) {
                        consume();
                        List<Node> args = parseArgList();
                        expect(TT.RPAREN);
                        yield new MethodCallNode(null, t.value(), args);
                    }
                    yield new IdentNode(t.value());
                }

                case LBRACKET -> {
                    consume();
                    List<Node> elems = new ArrayList<>();
                    if (!check(TT.RBRACKET)) {
                        elems.add(parseExpression());
                        while (check(TT.COMMA)) {
                            consume();
                            if (!check(TT.RBRACKET)) elems.add(parseExpression());
                        }
                    }
                    expect(TT.RBRACKET);
                    yield new ListLiteralNode(elems);
                }

                case LBRACE -> {
                    consume();
                    List<Map.Entry<String, Node>> entries = new ArrayList<>();
                    if (!check(TT.RBRACE)) {
                        entries.add(parseMapEntry());
                        while (check(TT.COMMA)) {
                            consume();
                            if (!check(TT.RBRACE)) entries.add(parseMapEntry());
                        }
                    }
                    expect(TT.RBRACE);
                    yield new MapLiteralNode(entries);
                }

                case LPAREN -> { consume(); Node e = parseExpression(); expect(TT.RPAREN); yield e; }

                // allow `for` as an expression so `x = for (item in list) { ... }` works
                case FOR -> parseForEach();

                default -> throw new ScriptException(
                        "Unexpected token '" + t.value() + "'", null, t.line());
            };
        }

        private Map.Entry<String, Node> parseMapEntry() throws ScriptException {
            String key;
            if (check(TT.IDENT))     key = consume().value();
            else if (check(TT.STR))  key = consume().value();
            else throw new ScriptException(
                    "Map key must be an identifier or string, got '" + peek().value() + "'",
                    null, peek().line());
            expect(TT.COLON);
            return Map.entry(key, parseExpression());
        }

        private Number parseNumber(String s) {
            return s.contains(".") ? Double.parseDouble(s) : Long.parseLong(s);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EVALUATOR
    // ─────────────────────────────────────────────────────────────────────────

    static class Evaluator {
        private final ScriptContext ctx;

        Evaluator(ScriptContext ctx) { this.ctx = ctx; }

        Object eval(Node node) throws ScriptException {
            return switch (node) {

                case BlockNode b -> {
                    Object last = null;
                    for (Node s : b.stmts()) last = eval(s);
                    yield last;
                }

                case AssignNode a -> {
                    Object val = eval(a.value());
                    ctx.setAttribute(a.name(), val, ScriptContext.ENGINE_SCOPE);
                    yield val;
                }

                case IfNode i -> {
                    for (IfNode.Branch br : i.branches())
                        if (isTruthy(eval(br.condition()))) yield eval(br.body());
                    yield i.elseBranch() != null ? eval(i.elseBranch()) : null;
                }

                case ForEachNode fe -> evalForEach(fe);
                case ExprStmtNode e  -> eval(e.expr());
                case LiteralNode  l  -> l.value();
                case IdentNode   id  -> resolveVariable(id.name());
                case UnaryOpNode  u  -> evalUnary(u);
                case BinaryOpNode b  -> evalBinary(b);
                case MethodCallNode m -> evalMethodCall(m);
                case IndexNode   idx -> evalIndex(idx);

                case ListLiteralNode ll -> {
                    List<Object> list = new ArrayList<>(ll.elements().size());
                    for (Node e : ll.elements()) list.add(eval(e));
                    yield list;
                }

                case MapLiteralNode ml -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (Map.Entry<String, Node> entry : ml.entries())
                        map.put(entry.getKey(), eval(entry.getValue()));
                    yield map;
                }
            };
        }

        // ── for-each ──────────────────────────────────────────────────────

        @SuppressWarnings("unchecked")
        private Object evalForEach(ForEachNode fe) throws ScriptException {
            Object collectionVal = eval(fe.collection());
            Iterable<?> items;
            if (collectionVal instanceof Map<?,?> map) {
                // Iterating a map yields its Map.Entry objects
                items = map.entrySet();
            } else if (collectionVal instanceof Iterable<?> it) {
                items = it;
            } else if (collectionVal != null && collectionVal.getClass().isArray()) {
                List<Object> tmp = new ArrayList<>(Array.getLength(collectionVal));
                for (int i = 0; i < Array.getLength(collectionVal); i++) tmp.add(Array.get(collectionVal, i));
                items = tmp;
            } else if (collectionVal instanceof String s) {
                List<Object> chars = new ArrayList<>(s.length());
                for (char c : s.toCharArray()) chars.add(String.valueOf(c));
                items = chars;
            } else {
                throw new ScriptException("for-each requires a List, array, Map, or String, got: " +
                        (collectionVal == null ? "null" : collectionVal.getClass().getSimpleName()));
            }

            List<Object> results = new ArrayList<>();
            int index = 0;
            for (Object item : items) {
                ctx.setAttribute(fe.itemVar(), item, ScriptContext.ENGINE_SCOPE);
                if (fe.indexVar() != null) {
                    ctx.setAttribute(fe.indexVar(), (long) index, ScriptContext.ENGINE_SCOPE);
                }
                results.add(eval(fe.body()));
                index++;
            }
            return results;
        }

        // ── variable resolution — searches all scopes in JSR-223 order ────

        private Object resolveVariable(String name) throws ScriptException {
            for (int scope : ctx.getScopes()) {
                Bindings b = ctx.getBindings(scope);
                if (b != null && b.containsKey(name)) return b.get(name);
            }
            throw new ScriptException("Undefined variable: '" + name + "'");
        }

        // ── index access ──────────────────────────────────────────────────

        @SuppressWarnings("unchecked")
        private Object evalIndex(IndexNode idx) throws ScriptException {
            Object target = eval(idx.target());
            Object index  = eval(idx.index());

            if (target == null) throw new ScriptException("Null reference in index access");

            if (target instanceof List<?> list) {
                int i = toInt(index);
                if (i < 0) i = list.size() + i;
                if (i < 0 || i >= list.size())
                    throw new ScriptException(
                            "List index out of bounds: " + toInt(index) + " (size " + list.size() + ")");
                return list.get(i);
            }

            if (target instanceof Map<?,?> map) {
                Object key = (index instanceof Number n) ? String.valueOf(n.longValue()) : index;
                if (!map.containsKey(key))
                    throw new ScriptException("Key not found in map: '" + key + "'");
                return ((Map<Object, Object>) map).get(key);
            }

            if (target.getClass().isArray()) {
                int i = toInt(index);
                if (i < 0) i = Array.getLength(target) + i;
                return Array.get(target, i);
            }

            throw new ScriptException(
                    "Cannot index into " + target.getClass().getSimpleName() +
                    " — must be a List, Map, or array");
        }

        // ── operators ─────────────────────────────────────────────────────

        private Object evalUnary(UnaryOpNode u) throws ScriptException {
            return switch (u.op()) {
                case "!" -> !isTruthy(eval(u.operand()));
                case "-" -> negate(eval(u.operand()));
                default  -> throw new ScriptException("Unknown unary operator: " + u.op());
            };
        }

        private Object evalBinary(BinaryOpNode b) throws ScriptException {
            if ("&&".equals(b.op())) return isTruthy(eval(b.left())) && isTruthy(eval(b.right()));
            if ("||".equals(b.op())) return isTruthy(eval(b.left())) || isTruthy(eval(b.right()));

            Object left  = eval(b.left());
            Object right = eval(b.right());

            return switch (b.op()) {
                case "==" -> numericAwareEquals(left, right);
                case "!=" -> !numericAwareEquals(left, right);
                case "<"  -> toDouble(left) <  toDouble(right);
                case ">"  -> toDouble(left) >  toDouble(right);
                case "<=" -> toDouble(left) <= toDouble(right);
                case ">=" -> toDouble(left) >= toDouble(right);
                case "+"  -> (left instanceof String || right instanceof String)
                        ? String.valueOf(left) + right
                        : toDouble(left) + toDouble(right);
                case "-"  -> toDouble(left) - toDouble(right);
                case "*"  -> toDouble(left) * toDouble(right);
                case "/"  -> toDouble(left) / toDouble(right);
                default   -> throw new ScriptException("Unknown operator: " + b.op());
            };
        }

        private boolean numericAwareEquals(Object a, Object b) {
            if (Objects.equals(a, b)) return true;
            if (a instanceof Number na && b instanceof Number nb)
                return na.doubleValue() == nb.doubleValue();
            return false;
        }

        // ── method / property dispatch ────────────────────────────────────

        @SuppressWarnings("unchecked")
        private Object evalMethodCall(MethodCallNode m) throws ScriptException {
            Object[] argVals = new Object[m.args().size()];
            for (int i = 0; i < m.args().size(); i++) argVals[i] = eval(m.args().get(i));

            if (m.target() == null) return evalBuiltIn(m.method(), argVals);

            Object target = eval(m.target());
            if (target == null)
                throw new ScriptException("Null reference: cannot call '" + m.method() + "' on null");

            // Map dot-access: map.key → map.get("key"), fall through to reflection for methods
            if (target instanceof Map<?,?> map && m.args().isEmpty()) {
                if (map.containsKey(m.method()))
                    return ((Map<Object, Object>) map).get(m.method());
            }

            return invoke(target, m.method(), argVals);
        }

        private Object evalBuiltIn(String name, Object[] args) throws ScriptException {
            switch (name) {
                case "print", "println" -> {
                    String out = Arrays.stream(args).map(String::valueOf).collect(Collectors.joining(" "));
                    Writer w = ctx.getWriter();
                    try {
                        if (w != null) {
                            w.write(name.equals("println") ? out + "\n" : out);
                            w.flush();
                        } else if (name.equals("println")) System.out.println(out);
                        else System.out.print(out);
                    } catch (IOException e) { throw new ScriptException(e); }
                    // Return the single argument as-is, or the joined string for multi-arg calls
                    return args.length == 1 ? args[0] : out;
                }
                case "str"  -> { return args.length == 1 ? String.valueOf(args[0]) : ""; }
                case "num"  -> { return args.length == 1 ? toDouble(args[0]) : 0.0; }
                case "bool" -> { return args.length == 1 && isTruthy(args[0]); }
                case "len"  -> {
                    if (args.length != 1) throw new ScriptException("len() expects 1 argument");
                    Object v = args[0];
                    if (v instanceof List<?> l)       return (long) l.size();
                    if (v instanceof Map<?,?> mp)     return (long) mp.size();
                    if (v instanceof String s)        return (long) s.length();
                    if (v != null && v.getClass().isArray()) return (long) Array.getLength(v);
                    throw new ScriptException("len() unsupported type: " +
                            (v == null ? "null" : v.getClass().getSimpleName()));
                }
            }
            throw new ScriptException("Undefined function: '" + name + "'");
        }

        // ── reflection ────────────────────────────────────────────────────

        private Object invoke(Object target, String methodName, Object[] args) throws ScriptException {
            Class<?> clazz = target.getClass();

            Method found = findMethod(clazz, methodName, args.length);
            if (found == null && args.length == 0) found = findMethod(clazz, toGetter("get", methodName), 0);
            if (found == null && args.length == 0) found = findMethod(clazz, toGetter("is",  methodName), 0);

            if (found == null)
                throw new ScriptException(String.format(
                        "Method not found: %s.%s(%d arg%s)",
                        clazz.getSimpleName(), methodName, args.length, args.length == 1 ? "" : "s"));
            try {
                found.setAccessible(true);
                return found.invoke(target, coerceArgs(found, args));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw new ScriptException(cause != null ? cause.getMessage() : e.getMessage());
            } catch (Exception e) {
                throw new ScriptException("Error calling " + methodName + ": " + e.getMessage());
            }
        }

        private Method findMethod(Class<?> clazz, String name, int argCount) {
            for (Class<?> c = clazz; c != null; c = c.getSuperclass())
                for (Method m : c.getDeclaredMethods())
                    if (m.getName().equals(name) && m.getParameterCount() == argCount) return m;
            for (Method m : clazz.getMethods())
                if (m.getName().equals(name) && m.getParameterCount() == argCount) return m;
            return null;
        }

        private String toGetter(String prefix, String name) {
            return prefix + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        private Object[] coerceArgs(Method method, Object[] args) {
            Class<?>[] params = method.getParameterTypes();
            Object[] out = new Object[args.length];
            for (int i = 0; i < args.length; i++) out[i] = coerce(args[i], params[i]);
            return out;
        }

        private Object coerce(Object val, Class<?> target) {
            if (val == null || target.isInstance(val)) return val;
            if (target == int.class     || target == Integer.class)  return ((Number) val).intValue();
            if (target == long.class    || target == Long.class)     return ((Number) val).longValue();
            if (target == double.class  || target == Double.class)   return ((Number) val).doubleValue();
            if (target == float.class   || target == Float.class)    return ((Number) val).floatValue();
            if (target == short.class   || target == Short.class)    return ((Number) val).shortValue();
            if (target == String.class)                              return String.valueOf(val);
            if (target == boolean.class || target == Boolean.class)  return isTruthy(val);
            return val;
        }

        // ── helpers ───────────────────────────────────────────────────────

        private boolean isTruthy(Object val) {
            return switch (val) {
                case null      -> false;
                case Boolean b -> b;
                case Number  n -> n.doubleValue() != 0;
                case String  s -> !s.isEmpty();
                default        -> true;
            };
        }

        private double toDouble(Object val) throws ScriptException {
            if (val instanceof Number n) return n.doubleValue();
            if (val instanceof String s) {
                try { return Double.parseDouble(s); } catch (NumberFormatException ignore) {}
            }
            throw new ScriptException("Cannot convert to number: " + val +
                    " (" + (val == null ? "null" : val.getClass().getSimpleName()) + ")");
        }

        private int toInt(Object val) throws ScriptException {
            if (val instanceof Number n) return n.intValue();
            throw new ScriptException("Index must be a number, got: " + val);
        }

        private Object negate(Object val) throws ScriptException {
            if (val instanceof Long   l) return -l;
            if (val instanceof Double d) return -d;
            if (val instanceof Number n) return -n.doubleValue();
            throw new ScriptException("Cannot negate: " + val);
        }
    }
}
