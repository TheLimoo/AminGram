package org.telegram.messenger.amin.pyplugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MVEL-subset evaluator for Python plugin menu conditions — M2.
 *
 * exteraGram menu items carry an MVEL {@code condition} expression.
 * Bundling a full MVEL evaluator is out of scope for M2 (and MVEL
 * itself would be a new dependency with its own reflection surface),
 * so AminGram evaluates a documented subset instead, in pure Java
 * (no Android or Chaquopy dependency — unit-testable on plain JDK):
 *
 * <pre>
 *   expr       := or
 *   or         := and (("or" | "||") and)*
 *   and        := not (("and" | "&&") not)*
 *   not        := "not" not | comparison
 *   comparison := primary (("==" | "!=" | "<" | ">" | "<=" | ">="
 *                | "contains" | "startswith") primary)?
 *   primary    := "(" expr ")" | literal | identifier (.identifier)*
 *   literal    := true | false | number | 'quoted' | "quoted"
 * </pre>
 *
 * Identifiers resolve against the context map, dotted
 * ({@code message.out}, {@code chat.type}); unknown identifiers
 * resolve to {@code null}. A comparison against {@code null} is
 * false, except {@code x != null}-style inequality against a
 * non-null literal, which is true when the identifier is unknown.
 * {@code contains} / {@code startswith} apply to strings only
 * (non-strings are stringified first). Numbers compare numerically;
 * anything else compares by string equality.
 *
 * An empty or blank condition is true (no restriction). A
 * syntactically invalid condition is false (fail-closed: the menu
 * item is hidden) and is logged by the caller.
 *
 * Deliberately NOT supported (rejected as a parse error):
 * method calls, indexing, arithmetic, assignment, {@code in},
 * ternary expressions, and any identifier outside the context map.
 * A plugin needing those must decide in its {@code on_menu_action}
 * instead — conditions are only a visibility filter.
 */
public final class PyMenuCondition {

    private PyMenuCondition() {
    }

    /** True when the condition allows showing the menu item. */
    public static boolean evaluate(String condition, Map<String, Object> context) {
        if (condition == null || condition.trim().isEmpty()) {
            return true;
        }
        try {
            Parser parser = new Parser(condition, context);
            Object result = parser.parseExpr();
            parser.expectEnd();
            return toBoolean(result);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        if (value instanceof String) {
            String s = ((String) value).trim().toLowerCase();
            return "true".equals(s) || "yes".equals(s) || "1".equals(s);
        }
        return value != null;
    }

    // ------------------------------------------------------------------
    // Tokenizer
    // ------------------------------------------------------------------

    private static final class Token {
        enum Kind { IDENT, NUMBER, STRING, OP, LPAREN, RPAREN, END }
        final Kind kind;
        final String text;

        Token(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private static List<Token> tokenize(String input) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                out.add(new Token(Token.Kind.LPAREN, "("));
                i++;
                continue;
            }
            if (c == ')') {
                out.add(new Token(Token.Kind.RPAREN, ")"));
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && input.charAt(i) != c) {
                    if (input.charAt(i) == '\\' && i + 1 < n) {
                        i++;
                    }
                    sb.append(input.charAt(i));
                    i++;
                }
                if (i >= n) {
                    throw new IllegalArgumentException("unterminated string");
                }
                i++;
                out.add(new Token(Token.Kind.STRING, sb.toString()));
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < n
                    && Character.isDigit(input.charAt(i + 1)))) {
                int start = i;
                i++;
                while (i < n && (Character.isDigit(input.charAt(i))
                        || input.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(Token.Kind.NUMBER, input.substring(start, i)));
                continue;
            }
            if (c == '=' || c == '!' || c == '<' || c == '>') {
                if (i + 1 < n && input.charAt(i + 1) == '=') {
                    out.add(new Token(Token.Kind.OP, input.substring(i, i + 2)));
                    i += 2;
                } else if (c == '<' || c == '>') {
                    out.add(new Token(Token.Kind.OP, String.valueOf(c)));
                    i++;
                } else {
                    throw new IllegalArgumentException("bad operator at " + i);
                }
                continue;
            }
            if (c == '&' && i + 1 < n && input.charAt(i + 1) == '&') {
                out.add(new Token(Token.Kind.OP, "&&"));
                i += 2;
                continue;
            }
            if (c == '|' && i + 1 < n && input.charAt(i + 1) == '|') {
                out.add(new Token(Token.Kind.OP, "||"));
                i += 2;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(input.charAt(i))
                        || input.charAt(i) == '_' || input.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(Token.Kind.IDENT, input.substring(start, i)));
                continue;
            }
            throw new IllegalArgumentException("unexpected char '" + c + "' at " + i);
        }
        out.add(new Token(Token.Kind.END, ""));
        return out;
    }

    // ------------------------------------------------------------------
    // Parser (recursive descent, evaluates as it parses)
    // ------------------------------------------------------------------

    private static final class Parser {
        private final List<Token> tokens;
        private final Map<String, Object> context;
        private int pos;

        Parser(String input, Map<String, Object> context) {
            this.tokens = tokenize(input);
            this.context = context;
        }

        Object parseExpr() {
            return parseOr();
        }

        void expectEnd() {
            if (peek().kind != Token.Kind.END) {
                throw new IllegalArgumentException(
                        "trailing tokens after expression: " + peek().text);
            }
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private boolean acceptOp(String op) {
            Token t = peek();
            if (t.kind == Token.Kind.OP && t.text.equals(op)) {
                pos++;
                return true;
            }
            return false;
        }

        private boolean acceptIdent(String word) {
            Token t = peek();
            if (t.kind == Token.Kind.IDENT && t.text.equalsIgnoreCase(word)) {
                pos++;
                return true;
            }
            return false;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (true) {
                if (acceptOp("||") || acceptIdent("or")) {
                    Object right = parseAnd();
                    left = toBoolean(left) || toBoolean(right);
                } else {
                    return left;
                }
            }
        }

        private Object parseAnd() {
            Object left = parseNot();
            while (true) {
                if (acceptOp("&&") || acceptIdent("and")) {
                    Object right = parseNot();
                    left = toBoolean(left) && toBoolean(right);
                } else {
                    return left;
                }
            }
        }

        private Object parseNot() {
            if (acceptIdent("not")) {
                return !toBoolean(parseNot());
            }
            return parseComparison();
        }

        private Object parseComparison() {
            Object left = parsePrimary();
            Token t = peek();
            String op = null;
            if (t.kind == Token.Kind.OP && ("==".equals(t.text) || "!=".equals(t.text)
                    || "<".equals(t.text) || ">".equals(t.text)
                    || "<=".equals(t.text) || ">=".equals(t.text))) {
                op = t.text;
                next();
            } else if (t.kind == Token.Kind.IDENT
                    && ("contains".equalsIgnoreCase(t.text)
                            || "startswith".equalsIgnoreCase(t.text))) {
                op = t.text.toLowerCase();
                next();
            }
            if (op == null) {
                return left;
            }
            Object right = parsePrimary();
            return compare(left, op, right);
        }

        private Object parsePrimary() {
            Token t = next();
            if (t.kind == Token.Kind.LPAREN) {
                Object inner = parseExpr();
                Token close = next();
                if (close.kind != Token.Kind.RPAREN) {
                    throw new IllegalArgumentException("expected ')'");
                }
                return inner;
            }
            if (t.kind == Token.Kind.STRING) {
                return t.text;
            }
            if (t.kind == Token.Kind.NUMBER) {
                try {
                    if (t.text.contains(".")) {
                        return Double.parseDouble(t.text);
                    }
                    return Long.parseLong(t.text);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad number: " + t.text);
                }
            }
            if (t.kind == Token.Kind.IDENT) {
                if ("true".equalsIgnoreCase(t.text)) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(t.text)) {
                    return Boolean.FALSE;
                }
                if ("null".equalsIgnoreCase(t.text)) {
                    return null;
                }
                return resolve(t.text);
            }
            throw new IllegalArgumentException("unexpected token: " + t.text);
        }

        private Object resolve(String dotted) {
            if (context == null) {
                return null;
            }
            String[] parts = dotted.split("\\.");
            Object current = context.get(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                if (current instanceof Map) {
                    current = ((Map<?, ?>) current).get(parts[i]);
                } else {
                    return null;
                }
                if (current == null) {
                    return null;
                }
            }
            return current;
        }

        private static boolean compare(Object left, String op, Object right) {
            if ("contains".equals(op) || "startswith".equals(op)) {
                if (left == null || right == null) {
                    return false;
                }
                String hay = String.valueOf(left);
                String needle = String.valueOf(right);
                return "contains".equals(op) ? hay.contains(needle)
                        : hay.startsWith(needle);
            }
            if (left == null || right == null) {
                if ("==".equals(op)) {
                    return left == null && right == null;
                }
                if ("!=".equals(op)) {
                    return (left == null) != (right == null);
                }
                return false;
            }
            if (left instanceof Number && right instanceof Number) {
                double a = ((Number) left).doubleValue();
                double b = ((Number) right).doubleValue();
                if ("==".equals(op)) {
                    return a == b;
                }
                if ("!=".equals(op)) {
                    return a != b;
                }
                if ("<".equals(op)) {
                    return a < b;
                }
                if (">".equals(op)) {
                    return a > b;
                }
                if ("<=".equals(op)) {
                    return a <= b;
                }
                return a >= b;
            }
            if (left instanceof Boolean && right instanceof Boolean) {
                if ("==".equals(op)) {
                    return left.equals(right);
                }
                if ("!=".equals(op)) {
                    return !left.equals(right);
                }
                return false;
            }
            String a = String.valueOf(left);
            String b = String.valueOf(right);
            int cmp = a.compareTo(b);
            if ("==".equals(op)) {
                return cmp == 0;
            }
            if ("!=".equals(op)) {
                return cmp != 0;
            }
            if ("<".equals(op)) {
                return cmp < 0;
            }
            if (">".equals(op)) {
                return cmp > 0;
            }
            if ("<=".equals(op)) {
                return cmp <= 0;
            }
            return cmp >= 0;
        }
    }
}
