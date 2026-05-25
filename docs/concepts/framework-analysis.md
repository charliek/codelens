# Framework Analysis

codelens answers *structural* questions about JVM bytecode — what implements an
interface, what a method calls, what references a type, how classes depend on
one another. It deliberately stops there. It does not ship framework-specific
features like "list the request handlers", "find the ORM entities", or "estimate
this migration".

That is by design. Framework-specific analysis decomposes into three layers,
and only one of them belongs in the tool:

| Layer | Example | Where it lives |
|-------|---------|----------------|
| **Data** | "find implementations of interface X", "extract the calls a method makes", "find references to type Y" | the tool — exhaustive bytecode facts |
| **Knowledge** | which FQNs are a framework's handler interface, its async primitives, its DI module base class | a skill (or your own notes) — an LLM already knows most of this |
| **Judgment** | complexity tiers, migration-effort estimates, anti-pattern severities | the consumer — judged from the raw facts, with reasoning shown |

The tool serves the **data** layer as a small set of general primitives:

| Primitive | Command | Answers |
|-----------|---------|---------|
| Implementations | `codelens classes implementations <fqn>` | who implements/extends a type |
| Annotations | `codelens annotations usages <fqn>` | who is annotated with X |
| Calls (forward) | `codelens calls <fqn> [--method m]` | what invocations a method makes (with constant args) |
| Cross-reference (inverse) | `codelens xref <typeFqn>` | everything that references a type |
| Dependency graph | `codelens deps` / `deps foundation` | project structure and the most depended-on classes |
| Source | `codelens source show <fqn>` | exact source or a bytecode-derived stub |

## Composing a framework question

"Where are this app's HTTP request handlers, and what do they call?" has no
dedicated command. You compose it:

```bash
# Knowledge: this framework's handler interface is com.example.web.Handler.
codelens classes implementations com.example.web.Handler

# Data: what does one of them invoke?
codelens calls com.example.web.UserHandler --method handle

# Data: who depends on a shared service it uses?
codelens xref com.example.service.UserService
```

The framework knowledge ("the handler interface is …") comes from you or a
skill; codelens supplies the exhaustive facts.

## Skills as the worked example

codelens publishes Claude Code [skills](https://github.com/charliek/codelens/tree/main/skills)
that encode exactly these recipes. The **`codelens-ratpack-analysis`** skill is
the canonical worked example: it reproduces a framework-specific migration
assessment (handlers, async usage, DI bindings, routes, anti-patterns,
migration order) entirely as recipes over the general primitives, with the
Ratpack-specific knowledge kept in the skill's reference docs. Nothing
Ratpack-specific lives in the tool itself — which is what lets the same
primitives analyze Spring, Micronaut, or any other JVM framework just as well.
