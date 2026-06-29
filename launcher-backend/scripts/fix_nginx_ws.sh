#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
#  fix_nginx_ws.sh  —  Ensure nginx forwards WebSocket Upgrade
#  headers for the launcher backend (port 8002).
#
#  This script is rsynced to the VPS by the GitHub Actions workflow
#  and executed there.  It is also unit-tested locally (see the
#  bottom of this file — TEST=1 ./fix_nginx_ws.sh).
#
#  What it does:
#    1. Finds EVERY nginx config file that contains a `proxy_pass`
#       to 127.0.0.1:8002 or localhost:8002 (the launcher backend).
#    2. For each such config, parses the location blocks targeting
#       :8002.  If the block already contains a
#       `proxy_set_header Upgrade $http_upgrade;` line, it skips.
#       Otherwise it injects the four WS-upgrade directives + a
#       long idle timeout right before the proxy_pass line.
#    3. Runs `nginx -t` and reloads nginx if the config is valid;
#       rolls back to the .bak.ws.* snapshot if not.
#
#  Idempotent: re-running is a no-op once every :8002 block has the
#  Upgrade header.
# ─────────────────────────────────────────────────────────────────

set -u

# When invoked with TEST=1, run the self-tests at the bottom of
# this file instead of mutating the real nginx config.
TEST=${TEST:-0}

log() { echo "[fix-nginx-ws] $*"; }

# Block-scoped check: returns "yes" if the `location` block whose
# body contains `proxy_pass ...:8002` ALREADY has a
# `proxy_http_version 1.1;` line (which is the signal directive
# we use for "this block has been patched at some point").
# Returns "no" if the block exists but is unpatched.
# Returns ""   if no :8002 block exists in the file.
#
# We use `proxy_http_version` as the signal (not `Upgrade`) because
# a previous partially-broken patch may have injected
# `proxy_http_version` WITHOUT the Upgrade headers — and we don't
# want to double-inject and trip the "duplicate directive" error.
ws_block_status() {
    local f="$1"
    awk '
        /\{/                                                                 { phv = 0 }
        /proxy_http_version[[:space:]]+1\.1/                                 { phv = 1 }
        /proxy_pass[[:space:]]+http:\/\/(127\.0\.0\.1|localhost):8002/ {
            print (phv ? "yes" : "no")
            exit
        }
    ' "$f"
}

# Remove ANY of the four WS-upgrade-related directives from the
# `location` block containing `proxy_pass ...:8002`.  Used to
# normalise the block before a fresh injection so we never end up
# with duplicate `proxy_http_version` lines from a half-applied
# previous patch.  Lines OUTSIDE the :8002 block (e.g. the apex
# Vesper backend block on :8001) are untouched.
strip_ws_directives_from_8002_block() {
    local f="$1"
    awk '
        function emit_buf(   i) { for (i = 1; i <= n; i++) print buf[i]; n = 0 }
        # When we see a new `{`, flush whatever we were buffering
        # (no :8002 in it → keep verbatim) and start a fresh buffer.
        /\{/ {
            if (in_block) { emit_buf() }
            in_block = 1
            n = 0
            buf[++n] = $0
            next
        }
        # Inside a buffered block, hold lines until we know whether
        # this block targets :8002.
        in_block {
            buf[++n] = $0
            if (/proxy_pass[[:space:]]+http:\/\/(127\.0\.0\.1|localhost):8002/) {
                is_8002 = 1
            }
            if (/\}/) {
                if (is_8002) {
                    # Strip WS-upgrade-related directives from this block.
                    for (i = 1; i <= n; i++) {
                        if (buf[i] ~ /proxy_http_version[[:space:]]+1\.1/)             continue
                        if (buf[i] ~ /proxy_set_header[[:space:]]+Upgrade/)            continue
                        if (buf[i] ~ /proxy_set_header[[:space:]]+Connection[[:space:]]+"upgrade"/) continue
                        if (buf[i] ~ /proxy_read_timeout[[:space:]]+3600s/)            continue
                        if (buf[i] ~ /proxy_send_timeout[[:space:]]+3600s/)            continue
                        print buf[i]
                    }
                } else {
                    emit_buf()
                }
                in_block = 0
                is_8002  = 0
                n = 0
                next
            }
            next
        }
        # Outside any buffered block — emit verbatim.
        { print }
    ' "$f" > "$f.tmp.ws" && mv "$f.tmp.ws" "$f"
}

# Inject the four required directives + long idle timeouts
# immediately BEFORE each proxy_pass line targeting :8002.
inject_ws_directives() {
    local f="$1"
    sed -i -E '/proxy_pass[[:space:]]+http:\/\/(127\.0\.0\.1|localhost):8002/i\        proxy_http_version 1.1;\n        proxy_set_header Upgrade $http_upgrade;\n        proxy_set_header Connection "upgrade";\n        proxy_read_timeout 3600s;\n        proxy_send_timeout 3600s;' "$f"
}

# Find every nginx config file that mentions :8002.
# Constraints:
#   • Only look at directories nginx actually loads from
#     (`sites-enabled/` and `conf.d/`).  `sites-available/` is just
#     inventory — patching it would inject a SECOND copy of the
#     directives via the `sites-enabled/` symlink and trip
#     nginx -t with "duplicate directive".
#   • Skip our own backup files (`*.bak.ws.*`, `*.bak`).
#   • De-duplicate by canonical path (realpath) so a symlinked file
#     isn't processed twice.
find_launcher_configs() {
    {
        grep -lER 'proxy_pass[[:space:]]+http://(127\.0\.0\.1|localhost):8002' \
            /etc/nginx/sites-enabled /etc/nginx/conf.d 2>/dev/null
    } \
        | grep -v -E '\.bak(\.|$)' \
        | while IFS= read -r f; do
              # Resolve symlink → real path so we only patch each
              # actual file once even when listed under both
              # sites-enabled (symlink) and sites-available (target).
              # `readlink -f` falls back to the path itself if not a link.
              realpath -m "$f" 2>/dev/null || readlink -f "$f" 2>/dev/null || echo "$f"
          done \
        | awk '!seen[$0]++'
}

patch_one() {
    local f="$1"
    local status
    status=$(ws_block_status "$f")
    log "  $f → ws_block_status=${status:-no_8002_block}"
    if [ -z "$status" ]; then
        log "    (no proxy_pass to :8002 in this file — skipping)"
        return 0
    fi

    # Always back up, always strip, always inject — a single
    # deterministic end state regardless of what previous (possibly
    # half-broken) patches left behind.  If nginx -t hates the
    # result, we roll back from the backup.
    local bak="$f.bak.ws.$(date +%s)"
    cp -p "$f" "$bak"
    log "    backed up to $bak"
    strip_ws_directives_from_8002_block "$f"
    inject_ws_directives "$f"
    # If the file is byte-identical to the backup after our work,
    # there was nothing to fix.  Skip claiming "changed" so the
    # workflow doesn't reload nginx for no reason.
    if cmp -s "$f" "$bak"; then
        log "    (no change after strip+inject — already canonical)"
        rm -f "$bak"
        return 0
    fi
    log "    strip + inject complete (config differs from backup)"
    return 1   # signal: this file changed
}

run_real() {
    log "scanning nginx configs for launcher backend proxy_pass…"
    local CONFS
    CONFS=$(find_launcher_configs || true)

    if [ -z "$CONFS" ]; then
        log "no nginx config files reference :8002 — nothing to patch."
        log "Remote Support will not work until nginx is configured to proxy /launcher/ to 127.0.0.1:8002."
        exit 0
    fi

    log "found $(echo "$CONFS" | wc -l) candidate config file(s):"
    echo "$CONFS" | sed 's/^/    /'

    local CHANGED=0
    while IFS= read -r f; do
        [ -z "$f" ] && continue
        if ! patch_one "$f"; then
            CHANGED=1
        fi
    done <<< "$CONFS"

    if [ "$CHANGED" -eq 0 ]; then
        log "no changes were necessary — nginx already configured correctly."
        exit 0
    fi

    log "validating new nginx config…"
    if nginx -t 2>&1; then
        log "config OK; reloading nginx"
        systemctl reload nginx 2>/dev/null \
            || nginx -s reload 2>/dev/null \
            || log "  (nginx reload command unavailable — manual reload may be needed)"
        log "done."
    else
        log "⚠️  nginx -t FAILED — rolling back every file we touched"
        while IFS= read -r f; do
            [ -z "$f" ] && continue
            local newest_bak
            newest_bak=$(ls -t "$f.bak.ws."* 2>/dev/null | head -1)
            if [ -n "$newest_bak" ] && [ -f "$newest_bak" ]; then
                cp -p "$newest_bak" "$f"
                log "  restored $f from $newest_bak"
            fi
        done <<< "$CONFS"
        exit 1
    fi
}


# ─────────────────────────────────────────────────────────────────
#  Self-tests — run with `TEST=1 ./fix_nginx_ws.sh`
# ─────────────────────────────────────────────────────────────────
run_tests() {
    local tmp=$(mktemp -d)
    trap "rm -rf $tmp" EXIT
    local pass=0 fail=0

    assert_eq() {
        local name="$1" want="$2" got="$3"
        if [ "$want" = "$got" ]; then
            echo "  PASS  $name"
            pass=$((pass + 1))
        else
            echo "  FAIL  $name"
            echo "        want: $want"
            echo "        got:  $got"
            fail=$((fail + 1))
        fi
    }

    # ── Case 1: launcher block has NO Upgrade header, Vesper block does
    cat > "$tmp/c1.conf" <<'EOF'
server {
    listen 443 ssl;
    server_name onnowhub.com;
    location /api/watch-party/ {
        proxy_pass http://127.0.0.1:8001;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
    location /launcher/ {
        proxy_pass http://127.0.0.1:8002/;
        proxy_set_header Host $host;
    }
}
EOF
    assert_eq "c1: pre-patch detect" "no" "$(ws_block_status "$tmp/c1.conf")"
    inject_ws_directives "$tmp/c1.conf"
    assert_eq "c1: post-patch detect" "yes" "$(ws_block_status "$tmp/c1.conf")"
    grep -q 'proxy_http_version 1.1' "$tmp/c1.conf" \
        && assert_eq "c1: proxy_http_version injected" "yes" "yes" \
        || assert_eq "c1: proxy_http_version injected" "yes" "no"

    # ── Case 2: launcher block ALREADY has Upgrade header
    cat > "$tmp/c2.conf" <<'EOF'
server {
    server_name onnowhub.com;
    location /launcher/ {
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_pass http://127.0.0.1:8002/;
    }
}
EOF
    assert_eq "c2: already patched" "yes" "$(ws_block_status "$tmp/c2.conf")"

    # ── Case 3: localhost:8002 (different host literal)
    cat > "$tmp/c3.conf" <<'EOF'
server {
    location /launcher/ {
        proxy_pass http://localhost:8002/;
    }
}
EOF
    assert_eq "c3: localhost variant detected" "no" "$(ws_block_status "$tmp/c3.conf")"
    inject_ws_directives "$tmp/c3.conf"
    assert_eq "c3: localhost variant patched" "yes" "$(ws_block_status "$tmp/c3.conf")"

    # ── Case 4: no :8002 block at all
    cat > "$tmp/c4.conf" <<'EOF'
server {
    location / {
        proxy_pass http://127.0.0.1:8001;
    }
}
EOF
    assert_eq "c4: no :8002 block" "" "$(ws_block_status "$tmp/c4.conf")"

    # ── Case 5: multiple :8002 blocks in one file (assets + admin)
    cat > "$tmp/c5.conf" <<'EOF'
server {
    location /launcher/admin/ {
        proxy_pass http://127.0.0.1:8002;
    }
    location /launcher/ {
        proxy_pass http://127.0.0.1:8002;
    }
}
EOF
    inject_ws_directives "$tmp/c5.conf"
    # Both blocks should now have the Upgrade header.
    local upg_count
    upg_count=$(grep -c 'proxy_set_header Upgrade' "$tmp/c5.conf")
    assert_eq "c5: both :8002 blocks patched" "2" "$upg_count"

    # ── Case 6: idempotency on a fully-patched file
    # Re-running patch_one on c1 (already patched after step above)
    # MUST be a no-op — strip + inject produces an identical file.
    patch_one "$tmp/c1.conf" || true
    local phv_count_c1
    phv_count_c1=$(grep -c 'proxy_http_version' "$tmp/c1.conf")
    # Expected: 2 — one in Vesper block, one in launcher block. NOT 3.
    assert_eq "c6: idempotent — no duplicate proxy_http_version" "2" "$phv_count_c1"
    local upg_count_c1
    upg_count_c1=$(grep -c 'proxy_set_header Upgrade' "$tmp/c1.conf")
    assert_eq "c6: idempotent — no duplicate Upgrade header" "2" "$upg_count_c1"

    # ── Case 7: PRODUCTION FAILURE RECOVERY — a previous botched
    # patch left behind proxy_http_version WITHOUT the Upgrade /
    # Connection headers (or with them duplicated).  The script must
    # CLEAN UP this junk and produce a canonical patched block.
    cat > "$tmp/c7.conf" <<'EOF'
server {
    location /launcher/ {
        proxy_http_version 1.1;
        proxy_pass http://127.0.0.1:8002/;
        proxy_set_header Host $host;
    }
}
EOF
    assert_eq "c7: partial-patch signal detected" "yes" "$(ws_block_status "$tmp/c7.conf")"
    patch_one "$tmp/c7.conf" || true
    local phv_count_c7 upg_count_c7 conn_count_c7
    phv_count_c7=$(grep -c 'proxy_http_version' "$tmp/c7.conf")
    upg_count_c7=$(grep -c 'proxy_set_header Upgrade' "$tmp/c7.conf")
    conn_count_c7=$(grep -c 'proxy_set_header Connection "upgrade"' "$tmp/c7.conf")
    assert_eq "c7: recovery — exactly one proxy_http_version" "1" "$phv_count_c7"
    assert_eq "c7: recovery — Upgrade header present"       "1" "$upg_count_c7"
    assert_eq "c7: recovery — Connection upgrade present"   "1" "$conn_count_c7"

    # ── Case 7b: nginx -t would have failed on a duplicate.  After
    # patch_one, no directive should appear twice in the launcher block.
    local dup_count_c7b
    dup_count_c7b=$(awk '/location \/launcher/,/}/' "$tmp/c7.conf" | grep -c 'proxy_http_version')
    assert_eq "c7b: no duplicate in launcher block"         "1" "$dup_count_c7b"

    # ── Case 8: strip_ws_directives_from_8002_block leaves the
    # Vesper block intact while cleaning the launcher block.
    cat > "$tmp/c8.conf" <<'EOF'
server {
    location /api/watch-party/ {
        proxy_pass http://127.0.0.1:8001;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
    location /launcher/ {
        proxy_http_version 1.1;
        proxy_read_timeout 3600s;
        proxy_pass http://127.0.0.1:8002/;
        proxy_set_header Host $host;
    }
}
EOF
    strip_ws_directives_from_8002_block "$tmp/c8.conf"
    # Vesper block must still have its WS directives untouched.
    local vesper_phv
    vesper_phv=$(awk '/location \/api\/watch-party/,/}/' "$tmp/c8.conf" | grep -c 'proxy_http_version')
    assert_eq "c8: Vesper block untouched after strip" "1" "$vesper_phv"
    # Launcher block must be CLEAN of WS directives now.
    local launcher_phv
    launcher_phv=$(awk '/location \/launcher\//,/}/' "$tmp/c8.conf" | grep -c 'proxy_http_version')
    assert_eq "c8: launcher block cleaned by strip" "0" "$launcher_phv"

    # ── Case 9: DUPLICATE-DIRECTIVE failure mode — file already
    # contains DUPLICATED proxy_http_version (this is the actual
    # state currently sitting on the VPS that caused the last 3
    # deploys to fail).  patch_one MUST normalise it to a single
    # canonical block.
    cat > "$tmp/c9.conf" <<'EOF'
server {
    location /launcher/ {
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_pass http://127.0.0.1:8002/;
        proxy_set_header Host $host;
    }
}
EOF
    patch_one "$tmp/c9.conf" || true
    local phv_count_c9
    phv_count_c9=$(grep -c 'proxy_http_version' "$tmp/c9.conf")
    assert_eq "c9: deduplicated to exactly one proxy_http_version" "1" "$phv_count_c9"

    echo ""
    echo "── Test summary: $pass passed, $fail failed ──"
    [ "$fail" -eq 0 ]
}

if [ "$TEST" = "1" ]; then
    run_tests
else
    run_real
fi
