---
title: MiniScript reference
sidebar_position: 8
---

# MiniScript reference

**MiniScript** is a small, zero-dependency expression language built into the
workflow engine. It is always registered (language name `mini`), so
`mini:`-prefixed expressions work without adding any `mc-workflow-code-*`
module. It is also a standalone JSR-223 engine (`mc-script-mini`) you can use on
its own.

Its sweet spot is JSON-shaped data: list and map literals, dot/index access, and
the handful of operators and built-ins you need to compute and decide — nothing
more.

## In a workflow

Prefix any expression with `mini:`:

```java
new VariableAssignment("greeting", "mini: \"Hello \" + name + \"!\"")
new VariableAssignment("total",    "mini: price * qty")
```

In an `IfStep` condition:

```java
cond.setCondition("mini: score >= 90");
```

## Syntax

### Comments and primitives

```js
// a comment
x    = 42
name = "hello"
flag = true
```

### Lists and maps

```js
// list literal -> java.util.ArrayList
ids  = ["a", "b", "c"]
nums = [1, 2, x + 1]

// map literal -> java.util.LinkedHashMap
person = { id: "1", name: "David", age: 40 }
mixed  = { "key-with-dash": 99, nested: { x: 1 } }
```

### Access

```js
first = ids[0]
last  = ids[-1]        // negative index on lists
third = nums[x - 1]

david  = person["name"] // string key
david2 = person.name    // dot-access (same thing)

deep = data.user.tags[1] // nested
```

### Operators

```
==  !=  <  >  <=  >=     comparison
&&  ||  !                logical
+  -  *  /               arithmetic ( + also concatenates strings )
```

### Conditionals

```js
if (config.env == "prod" && !config.debug) {
    println("Production, debug off")
} else if (config.debug) {
    println("Debug mode")
} else {
    println("Other")
}
```

### Variables and scope

MiniScript has **no block scoping** — every assignment goes into one flat,
script-wide scope. There is no `let`/`var` and no per-block isolation:

```js
if (flag) {
    msg = "yes"     // not local to the if — visible afterwards
}
println(msg)        // works; "msg" leaked out of the block
```

This includes **loop variables**: the `item` (and index) of a `for-each` stay
defined after the loop, holding their last value. Reuse a name and you overwrite
the outer one.

```js
for (item in items) { ... }
println(item)       // still defined — the last element
```

In a workflow, the script's variables are bound from / written back to the
step's [workflow variable scope](./variable-scope.md) (a `CodeStep` with the
default `injectVariables` / `exportVariables`). The hierarchical workflow scoping
applies at that boundary — but *within* a single MiniScript snippet everything is
flat.

### Loops

`for-each` works over a List, array, Map or String:

```js
for (item in items) {
    println(item)
}

// with index
for (item, i in items) {
    println(i + ": " + item)
}

// a for loop's value is the list of each iteration's last expression
doubled = for (n in nums) { n * 2 }
```

### Calling Java methods

Values passed in from Java can be used directly — methods and JavaBean
properties both work:

```js
result = order.process(x, "arg")
size   = ids.size()
status = order.status      // calls getStatus() automatically
```

### Built-ins

```
print(...)  println(...)   output
str(x)                     to string
num(x)                     to number
bool(x)                    to boolean
len(x)                     length of list / map / string
```

## Standalone (JSR-223)

Outside the workflow engine, MiniScript is a normal JSR-223 engine — obtain it
by name or via the factory:

```java
import javax.script.ScriptEngine;
import ai.mindconnect.script.mini.MiniScriptEngineFactory;

ScriptEngine engine = new MiniScriptEngineFactory().getScriptEngine();

engine.put("order", myOrder);            // expose a Java object
engine.eval("""
    summary = { count: len(order.items), label: "items" }
    """);

@SuppressWarnings("unchecked")
Map<String,Object> summary = (Map<String,Object>) engine.get("summary");
// read computed values straight back into Java
```

Note that arithmetic always returns a `Double` — `7 * 7` is `49.0`, not `49`.

(The module ships no `META-INF/services` entry for `ScriptEngineFactory`, so
`ScriptEngineManager` does **not** discover it automatically — instantiate
`MiniScriptEngineFactory` directly, as above. The workflow engine registers it
explicitly for the same reason.)
