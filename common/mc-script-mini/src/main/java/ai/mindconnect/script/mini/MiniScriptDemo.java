package ai.mindconnect.script.mini;

import javax.script.*;

public class MiniScriptDemo {

    public static class Order {
        private final String id;
        private final String status;
        public Order(String id, String status) { this.id = id; this.status = status; }
        public String getId()     { return id; }
        public String getStatus() { return status; }
    }

    public static void main(String[] args) throws Exception {
        ScriptEngine engine = new MiniScriptEngineFactory().getScriptEngine();

        // ── 1. list literal & index access ───────────────────────────────
        System.out.println("=== 1. list literal + index ===");
        engine.eval("""
                fruits = ["apple", "banana", "cherry"]
                first  = fruits[0]
                last   = fruits[-1]
                println(first)
                println(last)
                println(len(fruits))
                """);

        // ── 2. map literal & dot-access ───────────────────────────────────
        System.out.println("\n=== 2. map literal + dot-access ===");
        engine.eval("""
                person = {id: "1", name: "David", age: 40}
                println(person.name)
                println(person["age"])
                println(len(person))
                """);

        // ── 3. string-keyed map (keys with hyphens) ────────────────────
        System.out.println("\n=== 3. string keys ===");
        engine.eval("""
                cfg = {"max-retries": 3, "timeout-ms": 5000}
                println(cfg["max-retries"])
                println(cfg["timeout-ms"])
                """);

        // ── 4. nested maps and lists ──────────────────────────────────────
        System.out.println("\n=== 4. nested structures ===");
        engine.eval("""
                data = {
                    user: {name: "Alice", active: true},
                    tags: ["java", "script", "mini"]
                }
                println(data.user.name)
                println(data.user.active)
                println(data.tags[1])
                """);

        // ── 5. list of maps (table-like data) ────────────────────────────
        System.out.println("\n=== 5. list of maps ===");
        engine.eval("""
                users = [
                    {name: "Alice", role: "admin"},
                    {name: "Bob",   role: "user"},
                    {name: "Carol", role: "user"}
                ]
                println(users[0].name)
                println(users[1]["role"])
                println(users[2].name)
                """);

        // ── 6. expressions as list elements / map values ──────────────────
        System.out.println("\n=== 6. dynamic list/map construction ===");
        engine.eval("""
                base = 10
                nums = [base, base * 2, base * 3]
                println(nums[0])
                println(nums[2])

                result = {value: base * 5, label: "x" + str(base)}
                println(result.value)
                println(result.label)
                """);

        // ── 7. calling methods on Java objects passed from outside ─────────
        System.out.println("\n=== 7. Java object method calls ===");
        engine.put("order", new Order("ORD-42", "SHIPPED"));
        engine.eval("""
                orderId = order.id
                status  = order.status
                println(orderId)
                println(status)

                if (status == "SHIPPED") {
                    println("Order is on its way!")
                } else {
                    println("Order is still processing")
                }
                """);

        // ── 8. conditional on map values ─────────────────────────────────
        System.out.println("\n=== 8. conditional on map values ===");
        engine.eval("""
                config = {retries: 3, debug: false, env: "prod"}
                if (config.env == "prod" && !config.debug) {
                    println("Production mode, debug off")
                } else if (config.debug) {
                    println("Debug mode")
                } else {
                    println("Other")
                }
                """);

        // ── 9. read computed value back in Java ───────────────────────────
        System.out.println("\n=== 9. read back in Java ===");
        engine.eval("summary = {count: 3, label: \"items\"}");
        @SuppressWarnings("unchecked")
        java.util.Map<String,Object> summary = (java.util.Map<String,Object>) engine.get("summary");
        System.out.println("count from Java: " + summary.get("count"));
        System.out.println("label from Java: " + summary.get("label"));

        System.out.println("\nDone.");
    }
}
