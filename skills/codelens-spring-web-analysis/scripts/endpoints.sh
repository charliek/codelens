#!/usr/bin/env bash
#
# endpoints.sh — compose a Spring MVC / annotated-WebFlux endpoint table from
# CodeLens primitives. It joins each controller's class-level @RequestMapping
# base path with every mapped method's path + HTTP verb (found via the meta
# @RequestMapping that @GetMapping/@PostMapping/etc. compose), and flags reactive
# (Mono/Flux/Publisher) return types.
#
# Usage:
#   endpoints.sh [PROJECT_DIR]
#
# Requires: a running codelens server for the project, `codelens` on PATH, `jq`.
#
# Annotation values are typed (#41 "structured annotation values"): a mapping's
# value/path is an ARRAY whose first STRING item is the (first) path, and the
# verb is the meta @RequestMapping's `method` ARRAY whose first ENUM item's value
# is the constant ("GET"). So paths read as `.parameters.value.items[0].value`
# (no bracket parsing), and a no-path @RequestMapping is an empty array
# (`items:[]`), i.e. `.items[0].value` is null → terminated in `// ""`.
#
# Known limits / deferred:
#   - First path / first verb only. Multi-path (`@RequestMapping({"/a","/b"})`)
#     and multi-verb mappings are intentionally shown as their first entry; the
#     full cross-product is a deliberate non-goal here (not a regression).
#   - WebFlux *functional* routes (RouterFunction beans) carry no annotations and
#     are NOT listed here — recover them with `codelens calls <RouterBean>`
#     (see the skill's WEBFLUX.md).
set -euo pipefail

PROJECT="${1:-}"
proj=()
[ -n "$PROJECT" ] && proj=(--project "$PROJECT")

RM="org.springframework.web.bind.annotation.RequestMapping"
CTRL="org.springframework.stereotype.Controller"   # meta-matches @RestController

# 1) class FQN -> class-level base path (value/path are @AliasFor aliases, each
#    an ARRAY of STRING; read the first item of whichever the author set).
declare -A BASE
while IFS= read -r cls; do
  [ -z "$cls" ] && continue
  BASE["$cls"]=$(codelens classes show "$cls" "${proj[@]}" --json 2>/dev/null \
    | jq -r --arg rm "$RM" '
        [ .classInfo.annotations[]? | select(.type==$rm)
          | (.parameters.value.items[0].value // .parameters.path.items[0].value // "") ]
        | map(select(. != "")) | (.[0] // "")')
done < <(codelens classes list --annotation "$CTRL" "${proj[@]}" --json 2>/dev/null | jq -r '.classes[]?.fqn')

# 2) one row per mapped handler method.
printf '%-7s %-34s %-46s %s\n' VERB PATH HANDLER RETURNS
codelens methods search --annotation "$RM" "${proj[@]}" --json 2>/dev/null \
| jq -c '.methods[]?' \
| while IFS= read -r m; do
    cls=$(jq -r '.classFqn' <<<"$m")
    name=$(jq -r '.method.name' <<<"$m")
    ret=$(jq -r '.method.returnType' <<<"$m")

    # verb: prefer the specific @{Get,Post,...}Mapping; else the meta method
    # ENUM value ("GET"), already the bare constant name.
    verb=$(jq -r '.method.annotations[]?.type
                  | capture("\\.(?<v>Get|Post|Put|Delete|Patch)Mapping$").v' <<<"$m" \
           | head -n1 | tr '[:lower:]' '[:upper:]')
    if [ -z "$verb" ]; then
      verb=$(jq -r --arg rm "$RM" '
        [ .method.annotations[]? | select(.type==$rm) | .parameters.method.items[]?.value ]
        | (.[0] // "")' <<<"$m")
      [ -z "$verb" ] && verb=ANY
    fi

    # method path: first STRING item of value/path on any *Mapping annotation.
    mpath=$(jq -r '
      [ .method.annotations[]? | select(.type|test("Mapping$"))
        | (.parameters.value.items[0].value // .parameters.path.items[0].value // "") ]
      | map(select(. != "")) | (.[0] // "")' <<<"$m")

    # join class base + method path with exactly one slash (avoid // and a trailing /)
    base="${BASE[$cls]:-}"
    mpath="${mpath#/}"
    full="${base%/}${mpath:+/}${mpath}"
    [ -z "$full" ] && full="(no path)"

    reactive=""
    case "$ret" in
      *reactor.core.publisher.*|*org.reactivestreams.Publisher*) reactive=" [reactive]" ;;
    esac

    # simplify the return type for display: drop package prefixes, incl. inside generics
    # (java.util.List<com.example.Product> -> List<Product>)
    ret_short=$(printf '%s' "$ret" | sed 's/\([A-Za-z_][A-Za-z0-9_]*\.\)\+//g')
    printf '%-7s %-34s %-46s %s\n' "$verb" "$full" "${cls##*.}.$name" "${ret_short}$reactive"
  done
