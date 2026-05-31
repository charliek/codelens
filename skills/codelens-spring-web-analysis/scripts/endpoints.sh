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
# Known limits (today, pre-#41 "structured annotation values"):
#   - Annotation values are stringified ("[/a, /b]"); this script strips the
#     brackets and uses the first path. Multi-path mappings show only the first.
#   - WebFlux *functional* routes (RouterFunction beans) carry no annotations and
#     are NOT listed here — recover them with `codelens calls <RouterBean>`
#     (see the skill's WEBFLUX.md).
set -euo pipefail

PROJECT="${1:-}"
proj=()
[ -n "$PROJECT" ] && proj=(--project "$PROJECT")

RM="org.springframework.web.bind.annotation.RequestMapping"
CTRL="org.springframework.stereotype.Controller"   # meta-matches @RestController

# "[/a, /b]" -> "/a, /b"; "[]" -> ""
unbracket() { sed -e 's/^\[//' -e 's/\]$//'; }

# 1) class FQN -> class-level base path (value/path are @AliasFor aliases).
declare -A BASE
while IFS= read -r cls; do
  [ -z "$cls" ] && continue
  BASE["$cls"]=$(codelens classes show "$cls" "${proj[@]}" --json 2>/dev/null \
    | jq -r --arg rm "$RM" '
        [ .classInfo.annotations[]? | select(.type==$rm)
          | (.parameters.value // ""), (.parameters.path // "") ]
        | map(select(. != "" and . != "[]")) | (.[0] // "")' \
    | unbracket)
done < <(codelens classes list --annotation "$CTRL" "${proj[@]}" --json 2>/dev/null | jq -r '.classes[]?.fqn')

# 2) one row per mapped handler method.
printf '%-7s %-34s %-46s %s\n' VERB PATH HANDLER RETURNS
codelens methods search --annotation "$RM" "${proj[@]}" --json 2>/dev/null \
| jq -c '.methods[]?' \
| while IFS= read -r m; do
    cls=$(jq -r '.classFqn' <<<"$m")
    name=$(jq -r '.method.name' <<<"$m")
    ret=$(jq -r '.method.returnType' <<<"$m")

    # verb: prefer the specific @{Get,Post,...}Mapping; else the meta method attr.
    verb=$(jq -r '.method.annotations[]?.type
                  | capture("\\.(?<v>Get|Post|Put|Delete|Patch)Mapping$").v' <<<"$m" \
           | head -n1 | tr '[:lower:]' '[:upper:]')
    if [ -z "$verb" ]; then
      verb=$(jq -r --arg rm "$RM" '.method.annotations[]? | select(.type==$rm) | (.parameters.method // "")' <<<"$m" \
             | head -n1 | unbracket | sed 's/.*RequestMethod\.//; s/,.*//')
      [ -z "$verb" ] && verb=ANY
    fi

    # method path: first non-empty value/path on any *Mapping annotation.
    mpath=$(jq -r '
      [ .method.annotations[]? | select(.type|test("Mapping$"))
        | (.parameters.value // ""), (.parameters.path // "") ]
      | map(select(. != "" and . != "[]")) | (.[0] // "")' <<<"$m" | unbracket)

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
