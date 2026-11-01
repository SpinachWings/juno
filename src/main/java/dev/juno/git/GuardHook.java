package dev.juno.git;

/**
 * The one shell script installed under every hook name in an agent's hooks folder (design §7.8). It refuses commits,
 * merges, pushes and ref updates onto the protected branches, then runs the repository's own hook of the same name.
 * Plain POSIX sh: Git for Windows runs hooks through its own sh.
 */
final class GuardHook {
    private GuardHook() {}

    static final String SCRIPT = """
            #!/bin/sh
            # Juno: protected-branch guard for an agent's worktree. Then the repository's own hook of the same name.
            hook=$(basename "$0")
            dir=$(cd "$(dirname "$0")" && pwd)
            protected="$dir/protected-branches"
            input=""
            case "$hook" in
              pre-push|post-rewrite|reference-transaction) input=$(cat) ;;
            esac

            is_protected() {
              [ -n "$1" ] && grep -Fxq -- "$1" "$protected" 2>/dev/null
            }

            refuse() {
              esc=$(printf '%s' "$2" | sed -e 's/[\\\\&|]/\\\\&/g')
              sed "s|@@BRANCH@@|$esc|g" "$dir/message-$1.txt" >&2
              exit 1
            }

            case "$hook" in
              pre-commit)
                branch=$(git symbolic-ref --quiet --short HEAD 2>/dev/null)
                is_protected "$branch" && refuse commit "$branch"
                ;;
              pre-merge-commit)
                branch=$(git symbolic-ref --quiet --short HEAD 2>/dev/null)
                is_protected "$branch" && refuse merge "$branch"
                ;;
              pre-push)
                # stdin lines: <local ref> <local sha> <remote ref> <remote sha>
                hit=$(printf '%s\\n' "$input" | while read -r lref lsha rref rsha; do
                  case "$rref" in
                    refs/heads/*) b=${rref#refs/heads/}; if is_protected "$b"; then echo "$b"; break; fi ;;
                  esac
                done)
                [ -n "$hit" ] && refuse push "$hit"
                ;;
              reference-transaction)
                if [ "$1" = "prepared" ]; then
                  hit=$(printf '%s\\n' "$input" | while read -r old new ref; do
                    [ "$old" = "$new" ] && continue
                    case "$ref" in
                      refs/heads/*) b=${ref#refs/heads/}; if is_protected "$b"; then echo "$b"; break; fi ;;
                    esac
                  done)
                  [ -n "$hit" ] && refuse update "$hit"
                fi
                ;;
            esac

            orig=$(cat "$dir/original-hooks-path" 2>/dev/null)
            if [ -n "$orig" ] && [ -f "$orig/$hook" ] && [ -x "$orig/$hook" ]; then
              case "$hook" in
                pre-push|post-rewrite|reference-transaction)
                  printf '%s\\n' "$input" | "$orig/$hook" "$@"
                  exit $?
                  ;;
                *)
                  exec "$orig/$hook" "$@"
                  ;;
              esac
            fi
            exit 0
            """;
}
