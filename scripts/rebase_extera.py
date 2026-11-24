#!/usr/bin/env python3
"""AminGram rebase onto real exteraGram v13.2.2.

Runs on the GitHub Actions runner. Working directory must be the AminGram
checkout root (branch extera-base), which holds patches/, TMessagesProj/
(the overlay) and this script.

Steps:
  1. Clone exteraGram tag v13.2.2 (recursive, shallow submodules) into extera/.
  2. Rebrand gradle.properties: APP_PACKAGE -> org.telegram.amingram,
     APP_VERSION_CODE 7303 -> 7304 (so the APK installs over .76, code 73039).
  3. Copy the selective AminGram overlay into extera/TMessagesProj.
  4. Apply the keeper patches HUNK BY HUNK (a patch whose TranslateController
     hunks fail still lands its MessagesController/UserConfig/... hunks).
     Full context first, -C1 fuzz as a fallback (git still requires a unique
     match). A hunk that would re-add a method exteraGram already has is
     skipped. Patch 0028's proguard hunk is intentionally not chased: its
     three member keeps are native in exteraGram, and its Xposed keep is for
     the dead DEX bridge.
  4b. Re-derive the SettingsActivity rows (patches 0001 + 0059) against
     exteraGram's real anchors: the Plugins row (id 90) and Resource Usage
     row (id 91) under an "AminGram" header, plus their switch cases.
  5. Rewire the hub (HubCommon.java) from the dead exteraless engine to the
     real com.exteragram.messenger.plugins API; gate the icon picker off
     (LauncherIconController does not exist in exteraGram); gate the font row
     off if patch 0023 did not apply fully; add the "Based on exteraGram"
     credit + link to the hub footer (ADDITIONAL_TERMS.md).
  6. FULL-delete the AI package (com.exteragram.messenger.ai) and every
     reference to it (imports, fields, if/else fallbacks, settings rows,
     backup entries).
  7. Re-derive the FreeTranslate hooks against exteraGram's real
     TranslateController (patch 0003's TranslateController hunks do not apply
     cleanly; the two small gates + the translate loop are re-applied here).
  8. Best-effort speed-boost hooks in FileLoadOperation/FileUploadOperation.
  9. Rebrand strings (exteraAppName -> AminGram, Preferences -> AminGram
     Preferences; AppName -> AminGram if still Telegram).
 10. Register the org.telegram.amingram (+ .beta) clients in exteraGram's own
     google-services.json (base client com.exteragram.messenger).
 11. Auto-extract any R.string.* the amin code needs that exteraGram does not
     define, from the overlay strings_oe_plugins.xml, into
     res/values/amingram_strings.xml. Fail if a needed string exists nowhere.
 12. Fail-fast verification greps (no exteraless / ai-package / LauncherIcon
     references left). Exit non-zero on any FAIL so the workflow stops
     BEFORE the multi-hour build.

No secrets are read or written by this script. API keys, keystore and
signing are the workflow's job (GitHub secrets -> API_KEYS file).
"""

import copy
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path.cwd()
EXTERA = ROOT / "extera"
TM = EXTERA / "TMessagesProj"
SRC = TM / "src" / "main"
OVERLAY = ROOT / "TMessagesProj"
PATCHES_DIR = ROOT / "patches"

FAILURES = []
WARNINGS = []
PATCH_STATS = {}  # patch stem -> (applied_hunks, skipped_hunks, [skip reasons])


def log(level, msg):
    print(f"[{level}] {msg}", flush=True)


def info(msg):
    log("INFO", msg)


def warn(msg):
    log("WARN", msg)
    WARNINGS.append(msg)


def fail(msg):
    log("FAIL", msg)
    FAILURES.append(msg)


def run(cmd, cwd=None, check=True):
    info("run: " + " ".join(str(c) for c in cmd))
    r = subprocess.run(cmd, cwd=str(cwd) if cwd else None,
                       capture_output=True, text=True)
    if r.returncode != 0:
        log("CMD-OUT", (r.stdout or "").strip()[-2000:])
        log("CMD-ERR", (r.stderr or "").strip()[-2000:])
        if check:
            raise RuntimeError(f"command failed ({r.returncode}): {' '.join(map(str, cmd))}")
    return r


def read(p):
    return Path(p).read_text(encoding="utf-8")


def write(p, s):
    Path(p).write_text(s, encoding="utf-8")


# --------------------------------------------------------------------------
# Java source helpers (brace-aware, string/comment-aware)
# --------------------------------------------------------------------------

def brace_span(text, open_idx):
    """Index just past the '}' matching the '{' at open_idx, or None."""
    depth = 0
    i = open_idx
    n = len(text)
    in_str = in_chr = False
    esc = False
    line_c = block_c = False
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if line_c:
            if c == "\n":
                line_c = False
        elif block_c:
            if c == "*" and nxt == "/":
                block_c = False
                i += 1
        elif in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        elif in_chr:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == "'":
                in_chr = False
        else:
            if c == "/" and nxt == "/":
                line_c = True
            elif c == "/" and nxt == "*":
                block_c = True
            elif c == '"':
                in_str = True
            elif c == "'":
                in_chr = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return i + 1
        i += 1
    return None


def line_start(text, idx):
    return text.rfind("\n", 0, idx) + 1


def line_end(text, idx):
    j = text.find("\n", idx)
    return len(text) if j == -1 else j


def delete_if_chain(text, cond_substr, exact=False):
    """Delete an `if (...cond_substr...) { ... }` plus any chained
    `else if (...) { ... }` / final `else { ... }`. With exact=True the
    condition must equal cond_substr exactly (so a gapView if whose
    condition merely CONTAINS the chain's condition is not matched).
    Returns (new_text, found)."""
    for m in re.finditer(r'\bif\s*\(', text):
        paren = text.find("(", m.start())
        depth = 0
        j = paren
        cond = None
        while j < len(text):
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
                if depth == 0:
                    cond = text[paren:j + 1]
                    break
            j += 1
        if cond is None:
            continue
        if exact:
            if cond.strip() != "(" + cond_substr + ")":
                continue
        elif cond_substr not in cond:
            continue
        brace = text.find("{", j)
        if brace == -1:
            continue
        end = brace_span(text, brace)
        if end is None:
            continue
        # chained else-if / else blocks
        while True:
            k = end
            while k < len(text) and text[k] in " \t\r\n":
                k += 1
            if not text.startswith("else", k):
                break
            ebrace = text.find("{", k)
            if ebrace == -1:
                break
            eend = brace_span(text, ebrace)
            if eend is None:
                break
            end = eend
        start = line_start(text, m.start())
        end = line_end(text, end - 1) + 1
        return text[:start] + text[end:], True
    return text, False


def delete_if_block(text, cond_substr):
    """Delete the first `if (...cond_substr...) { ... }` statement. No else.

    Returns (new_text, found)."""
    for m in re.finditer(r'\bif\s*\(', text):
        paren = text.find("(", m.start())
        # find matching close paren (parens only; strings are rare in conditions)
        depth = 0
        j = paren
        cond = None
        while j < len(text):
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
                if depth == 0:
                    cond = text[paren:j + 1]
                    break
            j += 1
        if cond is None or cond_substr not in cond:
            continue
        brace = text.find("{", j)
        if brace == -1:
            continue
        end = brace_span(text, brace)
        if end is None:
            continue
        start = line_start(text, m.start())
        # include the trailing newline of the close-brace line
        end = line_end(text, end - 1) + 1
        return text[:start] + text[end:], True
    return text, False


def replace_if_else_with_else(text, cond_substr):
    """Replace the first `if (...cond_substr...) { A } else { B }` with B's body.

    Returns (new_text, found)."""
    for m in re.finditer(r'\bif\s*\(', text):
        paren = text.find("(", m.start())
        depth = 0
        j = paren
        cond = None
        while j < len(text):
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
                if depth == 0:
                    cond = text[paren:j + 1]
                    break
            j += 1
        if cond is None or cond_substr not in cond:
            continue
        brace = text.find("{", j)
        if brace == -1:
            continue
        if_end = brace_span(text, brace)
        if if_end is None:
            continue
        k = if_end
        while k < len(text) and text[k] in " \t\r\n":
            k += 1
        if not text.startswith("else", k):
            continue
        ebrace = text.find("{", k)
        if ebrace == -1:
            continue
        else_end = brace_span(text, ebrace)
        if else_end is None:
            continue
        else_body = text[ebrace + 1:else_end - 1]
        start = line_start(text, m.start())
        end = line_end(text, else_end - 1) + 1
        return text[:start] + else_body.strip("\n") + "\n" + text[end:], True
    return text, False


def delete_import_lines(text, markers):
    """Delete import lines containing any marker. Returns (new_text, count)."""
    lines = text.split("\n")
    out = []
    n = 0
    for ln in lines:
        if ln.strip().startswith("import ") and any(s in ln for s in markers):
            n += 1
            continue
        out.append(ln)
    return "\n".join(out), n


def delete_lines_with(text, substr):
    """Delete every line containing substr. Returns (new_text, count)."""
    lines = text.split("\n")
    out = [ln for ln in lines if substr not in ln]
    return "\n".join(out), len(lines) - len(out)


def delete_statement(text, marker_re):
    """Delete statement(s) whose first line matches marker_re, balancing
    parens/braces. Used for Kotlin one-or-multi-line .addIf()/uButton()/case
    lines. marker_re is a regex (so ChatsItem.AI does not match
    ChatsItem.AI_FEATURES). Returns (new_text, count)."""
    rx = re.compile(marker_re)
    lines = text.split("\n")
    out = []
    i = 0
    n = 0
    while i < len(lines):
        if rx.search(lines[i]):
            depth = 0
            j = i
            while j < len(lines):
                depth += lines[j].count("(") - lines[j].count(")")
                depth += lines[j].count("{") - lines[j].count("}")
                j += 1
                if depth <= 0:
                    break
            n += j - i
            i = j
            continue
        out.append(lines[i])
        i += 1
    return "\n".join(out), n


def delete_method(text, signature_regex):
    """Delete a whole method whose signature line matches signature_regex.

    Returns (new_text, found)."""
    m = re.search(signature_regex, text, re.M)
    if not m:
        return text, False
    brace = text.find("{", m.start())
    if brace == -1:
        return text, False
    end = brace_span(text, brace)
    if end is None:
        return text, False
    start = line_start(text, m.start())
    end = line_end(text, end - 1) + 1
    return text[:start] + text[end:], True


def delete_case_block(text, case_substr, max_lines=60):
    """Delete a `case <case_substr>:` block.

    Braced / arrow cases (`case X: { ... }`, `case X -> { ... }`) are
    deleted by brace matching from the `{` on the case line — this is
    required because the body may contain `return` inside lambdas, which
    a naive break/return scan would stop at early (that bug left the
    OPTION_GENERATE body half-deleted in run 5).
    Unbraced cases fall back to a brace-depth-aware scan for break;/return
    at the case's top level. Returns (new_text, found)."""
    lines = text.split("\n")
    for i, ln in enumerate(lines):
        if ln.strip().startswith("case ") and case_substr in ln:
            brace = ln.find("{")
            if brace != -1:
                # offset of the `{` in the full text
                off = sum(len(x) + 1 for x in lines[:i]) + brace
                end = brace_span(text, off)
                if end is None:
                    return text, False
                start = line_start(text, sum(len(x) + 1 for x in lines[:i]))
                end = line_end(text, end - 1) + 1
                return text[:start] + text[end:], True
            # unbraced: scan for break;/return at depth 0
            depth = 0
            j = i
            while j < len(lines) and j - i < max_lines:
                depth += lines[j].count("{") - lines[j].count("}")
                s = lines[j].strip()
                if depth == 0 and (s == "break;" or s.startswith("return ")):
                    return "\n".join(lines[:i] + lines[j + 1:]), True
                j += 1
            return text, False
    return text, False


METHOD_RE = re.compile(
    r'^\s*(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?'
    r'[\w<>\[\].,? ]+?\s+(\w+)\s*\(([^)]*)\)\s*'
    r'(?:throws\s+[\w,\s]+)?\{\s*$')


def method_sig(line):
    """(name, param-type tuple) for a single-line method declaration, or None."""
    m = METHOD_RE.match(line)
    if not m:
        return None
    name = m.group(1)
    params = m.group(2).strip()
    types = []
    if params:
        for p in params.split(","):
            toks = [t for t in p.strip().split() if not t.startswith("@")]
            if len(toks) >= 2:
                types.append(" ".join(toks[:-1]))
            elif toks:
                types.append(toks[0])
    return (name, tuple(types))


def file_method_sigs(path):
    sigs = set()
    try:
        for ln in read(path).split("\n"):
            s = method_sig(ln)
            if s:
                sigs.add(s)
    except FileNotFoundError:
        pass
    return sigs


def grep_count(root, pattern, suffixes=(".java", ".kt")):
    """List of (path, lineno, line) matching pattern under root."""
    hits = []
    rx = re.compile(pattern)
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if not any(f.endswith(s) for s in suffixes):
                continue
            p = Path(dirpath) / f
            try:
                for i, ln in enumerate(read(p).split("\n"), 1):
                    if rx.search(ln):
                        hits.append((str(p), i, ln.strip()))
            except Exception:
                pass
    return hits

# --------------------------------------------------------------------------
# Step 1: clone exteraGram
# --------------------------------------------------------------------------

def step_clone():
    info("== step 1: clone exteraGram v13.2.2 ==")
    if EXTERA.exists():
        shutil.rmtree(EXTERA)
    run(["git", "clone", "--depth", "1", "--branch", "v13.2.2",
         "--recursive", "--shallow-submodules",
         "https://github.com/exteraSquad/exteraGram.git", "extera"], cwd=ROOT)
    for sub in ("TMessagesProj/lib/jlatexmath/jlatexmath",
                "TMessagesProj_Modules/media"):
        if not (EXTERA / sub).exists():
            warn(f"submodule {sub} missing after clone; running submodule update")
            run(["git", "submodule", "update", "--init", "--recursive",
                 "--depth", "1"], cwd=EXTERA)
            break
    for sub in ("TMessagesProj/lib/jlatexmath/jlatexmath",
                "TMessagesProj_Modules/media"):
        if not (EXTERA / sub).exists():
            fail(f"submodule {sub} still missing after update")
    pc = EXTERA / ("TMessagesProj/src/full/java/com/exteragram/messenger"
                   "/plugins/PluginsController.kt")
    if not pc.exists():
        fail("PluginsController.kt not found in the clone")
    else:
        info("clone OK; PluginsController.kt present")


# --------------------------------------------------------------------------
# Step 2: gradle.properties rebrand + version bump
# --------------------------------------------------------------------------

def step_gradle_properties():
    info("== step 2: gradle.properties ==")
    p = EXTERA / "gradle.properties"
    t = read(p)
    if "APP_PACKAGE=com.exteragram.messenger" not in t:
        fail("gradle.properties: APP_PACKAGE=com.exteragram.messenger not found")
        return
    t = t.replace("APP_PACKAGE=com.exteragram.messenger",
                  "APP_PACKAGE=org.telegram.amingram", 1)
    if "APP_VERSION_CODE=7303" not in t:
        fail("gradle.properties: APP_VERSION_CODE=7303 not found")
        return
    t = t.replace("APP_VERSION_CODE=7303", "APP_VERSION_CODE=7304", 1)
    write(p, t)
    info("APP_PACKAGE=org.telegram.amingram, APP_VERSION_CODE=7304 "
         "(versionCode 7304*10+9 = 73049)")


# --------------------------------------------------------------------------
# Step 3: selective overlay copy
# --------------------------------------------------------------------------

# (kind, overlay-relative source, extera-relative destination)
OVERLAY_COPY = [
    ("tree", "src/main/java/org/telegram/messenger/amin",
     "src/main/java/org/telegram/messenger/amin"),
    ("file", "src/main/java/org/telegram/ui/Wallet/WalletPluginFreeze.java",
     "src/main/java/org/telegram/ui/Wallet/WalletPluginFreeze.java"),
    ("tree", "src/main/java/tw/nekomimi",
     "src/main/java/tw/nekomimi"),
    ("glob", "src/main/res/drawable/amingram_*",
     "src/main/res"),
    ("glob", "src/main/res/drawable-*/msg_plugins.png",
     "src/main/res"),
    # Launcher icons: exteraGram's default icon lives in res-appicons
    # (build.gradle sourceSets: res.srcDirs includes both res and
    # res-appicons, and the same resource in both is a Gradle duplicate
    # error — run 6 failed on exactly this). The two default files
    # overwrite exteraGram's defaults in res-appicons; the 12 amingram
    # variants have unique names and go to res/.
    ("glob_to", "src/main/res/mipmap-anydpi-v26/ic_launcher*.xml",
     "src/main/res-appicons/mipmap-anydpi-v26"),
    ("glob_to", "src/main/res/mipmap-anydpi-v26/amingram_icon_*",
     "src/main/res/mipmap-anydpi-v26"),
    ("file", "src/main/assets/amingram/hub_marble.webp",
     "src/main/assets/amingram/hub_marble.webp"),
    ("glob", "src/main/assets/amingram_pyplugins/*.plugin",
     "src/main/assets/amingram_pyplugins"),
    ("tree", "src/main/assets/fonts/ryoku",
     "src/main/assets/fonts/ryoku"),
]


def step_overlay():
    info("== step 3: copy selective overlay ==")
    copied = 0
    for kind, src_rel, dst_rel in OVERLAY_COPY:
        src = OVERLAY / src_rel
        if kind == "tree":
            if not src.is_dir():
                fail(f"overlay tree missing: {src_rel}")
                continue
            dst = TM / dst_rel
            shutil.copytree(src, dst, dirs_exist_ok=True)
            n = sum(1 for _ in src.rglob("*") if _.is_file())
            copied += n
            info(f"tree {src_rel}: {n} files")
        elif kind == "file":
            if not src.is_file():
                fail(f"overlay file missing: {src_rel}")
                continue
            dst = TM / dst_rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
            copied += 1
            info(f"file {src_rel}")
        elif kind == "glob_to":
            # glob matches copied by basename into an explicit dst dir
            matches = sorted(glob.glob(str(src)))
            if not matches:
                fail(f"overlay glob matched nothing: {src_rel}")
                continue
            for m in matches:
                mp = Path(m)
                if not mp.is_file():
                    continue
                dst = TM / dst_rel / mp.name
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(mp, dst)
                copied += 1
            info(f"glob_to {src_rel} -> {dst_rel}: {len(matches)} files")
        elif kind == "glob":
            matches = sorted(glob.glob(str(src)))
            if not matches:
                fail(f"overlay glob matched nothing: {src_rel}")
                continue
            for m in matches:
                mp = Path(m)
                if not mp.is_file():
                    continue
                rel = mp.relative_to(OVERLAY)
                dst = TM / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(mp, dst)
                copied += 1
            info(f"glob {src_rel}: {len(matches)} files")
    # Exclusions (must NOT have been copied): the exteraless engine, the
    # python runtime, the CodeHighlighting facade, any AndroidManifest.
    bad = []
    amin_tree = TM / "src/main/java/org/telegram/messenger/amin"
    for p in amin_tree.rglob("*"):
        if not p.is_file():
            continue
        if "exteraless" in p.name or p.suffix == ".py":
            bad.append(str(p.relative_to(TM)))
        if p.name == "AndroidManifest.xml":
            bad.append(str(p.relative_to(TM)))
    if bad:
        fail(f"excluded overlay content present: {bad}")
    info(f"overlay copied: {copied} files")


# --------------------------------------------------------------------------
# Step 4: hunk-level patch application
# --------------------------------------------------------------------------

KEEPER_PATCHES = ["0003", "0004", "0006", "0011", "0012", "0013",
                  "0014", "0017", "0018", "0019", "0020", "0023", "0024",
                  "0028"]
# 0001 + 0059 are NOT applied: step 4b re-derives them entirely (imports,
# rows 90/91, cases 90/91). 0059's case hunk applied with -C1 fuzz in run 8
# even though its context (case 90) was absent, adding a lone case 91 that
# step 4b then duplicated — a javac "duplicate case label" failure.
# 0002 is NOT applied: gradle.properties is rebranded by step 2 (its context
# APP_PACKAGE=org.telegram.messenger does not match exteraGram's
# APP_PACKAGE=com.exteragram.messenger).
# Dropped: 0007 (icon picker, deferred), 0008/0009 (native in exteraGram),
# 0025, 0029, 0030, 0031-0034, 0036-0050, 0051-0057 (exteraless engine),
# 0058 (minify-off; exteraGram stays R8-on).


def split_patch(text):
    """Split a unified diff into (path, hunk_header, hunk_body_lines) hunks."""
    hunks = []
    cur_path = None
    cur_header = None
    cur_body = []
    for ln in text.split("\n"):
        if ln.startswith("diff --git "):
            if cur_path and cur_header:
                hunks.append((cur_path, cur_header, cur_body))
            cur_path = None
            cur_header = None
            cur_body = []
        elif ln.startswith("+++ b/"):
            cur_path = ln[len("+++ b/"):]
        elif ln.startswith("@@ "):
            if cur_path and cur_header:
                hunks.append((cur_path, cur_header, cur_body))
            cur_header = ln
            cur_body = []
        elif cur_header is not None:
            cur_body.append(ln)
    if cur_path and cur_header:
        hunks.append((cur_path, cur_header, cur_body))
    return hunks


def hunk_adds_duplicate_method(hunk_body, target_path):
    """True if the hunk adds a method exteraGram's target file already has."""
    sigs = file_method_sigs(target_path)
    if not sigs:
        return None
    for ln in hunk_body:
        if not ln.startswith("+") or ln.startswith("+++"):
            continue
        s = method_sig(ln[1:])
        if s and s in sigs:
            return s
    return None


def apply_patch_hunks(patch_path):
    """Apply one patch file hunk by hunk. Returns (applied, skipped, reasons).

    Full context first; if that fails, retry with -C1 (one line of context
    — git still requires a unique match, so a rescued hunk lands at the
    right place)."""
    text = read(patch_path)
    hunks = split_patch(text)
    applied = 0
    skipped = 0
    reasons = []
    for path, header, body in hunks:
        target = EXTERA / path
        dup = hunk_adds_duplicate_method(body, target)
        if dup:
            skipped += 1
            reasons.append(f"{path} {header[:40]}: DUPLICATE_METHOD {dup}")
            continue
        mini = (f"diff --git a/{path} b/{path}\n"
                f"--- a/{path}\n"
                f"+++ b/{path}\n"
                f"{header}\n" + "\n".join(body) + "\n")
        with tempfile.NamedTemporaryFile("w", suffix=".patch",
                                        delete=False) as tf:
            tf.write(mini)
            tmp = tf.name
        try:
            chk = run(["git", "apply", "--check", tmp], cwd=EXTERA,
                      check=False)
            fuzzed = False
            if chk.returncode != 0:
                chk1 = run(["git", "apply", "--check", "-C1", tmp],
                           cwd=EXTERA, check=False)
                if chk1.returncode != 0:
                    skipped += 1
                    reasons.append(f"{path} {header[:40]}: APPLY_CHECK_FAILED")
                    continue
                fuzzed = True
            run(["git", "apply", "-C1", tmp] if fuzzed
                else ["git", "apply", tmp], cwd=EXTERA)
            applied += 1
            if fuzzed:
                info(f"  {path} {header[:40]}: applied with -C1 fuzz")
        finally:
            os.unlink(tmp)
    return applied, skipped, reasons


def step_patches():
    info("== step 4: apply keeper patches (hunk level) ==")
    for patch in sorted(PATCHES_DIR.glob("*.patch")):
        stem = patch.name.split("-")[0]
        if stem not in KEEPER_PATCHES:
            continue
        applied, skipped, reasons = apply_patch_hunks(patch)
        PATCH_STATS[patch.name] = (applied, skipped, reasons)
        info(f"{patch.name}: applied={applied} skipped={skipped}")
        for r in reasons:
            warn(f"  skip {patch.name}: {r}")
    total_a = sum(v[0] for v in PATCH_STATS.values())
    total_s = sum(v[1] for v in PATCH_STATS.values())
    info(f"patches total: applied={total_a} hunks, skipped={total_s} hunks")


# --------------------------------------------------------------------------
# Step 4b: SettingsActivity rows (patches 0001 + 0059, re-derived)
# --------------------------------------------------------------------------
# exteraGram's SettingsActivity differs from stock 13.2.2 enough that the
# patch hunks fail even with -C1 fuzz: extra imports between TextHelper and
# UItem, a `case -1:` (exteraGram Preferences) before `case 1:`, and a
# 7-arg language row. The rows are re-derived here against exteraGram's
# real anchors (verified by direct read of v13.2.2):
#   imports  -> before `import org.telegram.ui.Components.UItem;`
#   rows     -> after the language row
#              `getString(R.string.SettingsLanguage), null,
#               LocaleController.getCurrentLanguageName()));`
#   cases    -> right after `        switch (item.id) {`
# The 6-arg SettingCell.Factory.of(id, top, bottom, icon, title, subtitle)
# overload exists in exteraGram (SettingsActivity L2971), and
# R.drawable.settings_features / settings_data both exist there.
# Every insertion is idempotent: if a patch hunk did land, it is skipped.

def step_settings_rows():
    info("== step 4b: SettingsActivity rows (0001 + 0059, re-derived) ==")
    p = SRC / "java/org/telegram/ui/SettingsActivity.java"
    t = read(p)

    # Imports.
    for cls in ("AminPluginsActivity", "AminResourcesActivity"):
        imp = (f"import org.telegram.messenger.amin.ui.{cls};")
        if imp in t:
            info(f"  import {cls} already present")
            continue
        anchor = "import org.telegram.ui.Components.UItem;"
        if anchor not in t:
            fail("SettingsActivity: UItem import anchor not found")
            return
        t = t.replace(anchor, imp + "\n" + anchor, 1)
        info(f"  import {cls} added")

    # Rows (header + 90 Plugins, 91 Resource Usage) after the language row.
    lang = ("getString(R.string.SettingsLanguage), null, "
            "LocaleController.getCurrentLanguageName()));")
    if lang not in t:
        fail("SettingsActivity: language-row anchor not found")
        return
    row90 = ("        items.add(SettingCell.Factory.of(90, "
             "IconBackgroundColors.PURPLE.top, "
             "IconBackgroundColors.PURPLE.bottom, "
             "R.drawable.settings_features, \"Plugins\", "
             "\"AminGram feature modules\"));")
    row91 = ("        items.add(SettingCell.Factory.of(91, "
             "IconBackgroundColors.BLUE_ALT.top, "
             "IconBackgroundColors.BLUE_ALT.bottom, "
             "R.drawable.settings_data, \"Resource Usage\", "
             "\"RAM, CPU and uptime\"));")
    header = '        items.add(UItem.asHeader("AminGram"));'
    if "Factory.of(90," in t:
        info("  row 90 (Plugins) already present")
    else:
        t = t.replace(lang, lang + "\n" + header + "\n" + row90, 1)
        info("  AminGram header + row 90 (Plugins) added")
    if "Factory.of(91," in t:
        info("  row 91 (Resource Usage) already present")
    else:
        if row90 in t:
            t = t.replace(row90, row90 + "\n" + row91, 1)
        else:
            t = t.replace(lang, lang + "\n" + row91, 1)
        info("  row 91 (Resource Usage) added")

    # Cases right after `switch (item.id) {`.
    switch = "        switch (item.id) {"
    if switch not in t:
        fail("SettingsActivity: switch (item.id) anchor not found")
        return
    # Cases right after `switch (item.id) {` — add each only if absent
    # (a patch could have added one of them alone; adding both blindly
    # duplicates the one that exists — run 8's case-91 failure).
    add = []
    if "case 90:" not in t:
        add.append("            case 90:\n"
                   "                presentSettingFragment(new AminPluginsActivity());\n"
                   "                break;")
    if "case 91:" not in t:
        add.append("            case 91:\n"
                   "                presentSettingFragment(new AminResourcesActivity());\n"
                   "                break;")
    if add:
        t = t.replace(switch, switch + "\n" + "\n".join(add), 1)
        info(f"  cases added: {len(add)}")
    else:
        info("  cases 90/91 already present")
    write(p, t)

# --------------------------------------------------------------------------
# Step 5: hub rewire (HubCommon.java) + gating + credit
# --------------------------------------------------------------------------

def step_hub():
    info("== step 5: hub rewire ==")
    hub = SRC / ("java/org/telegram/messenger/amin/ui/HubCommon.java")
    if not hub.is_file():
        fail("HubCommon.java not found in overlay copy")
        return
    t = read(hub)

    # 5a. exteraless package -> real exteraGram plugin package (all amin files).
    amin_java = list((SRC / "java/org/telegram/messenger/amin").rglob("*.java"))
    total_rewired = 0
    for jf in amin_java:
        s = read(jf)
        n = s.count("app.exteraless.plugins.")
        if n:
            s = s.replace("app.exteraless.plugins.",
                          "com.exteragram.messenger.plugins.")
            write(jf, s)
            total_rewired += n
            info(f"  rewired {jf.name}: {n} exteraless refs")
    if total_rewired == 0:
        warn("no app.exteraless.plugins. refs found in amin tree")
    t = read(hub)

    # 5b. controller.isEngineEnabled() -> ExteraConfig.getPluginsEngine().
    # The real PluginsController has no isEngineEnabled(); the pref lives on
    # ExteraConfig (top-level var, @file:JvmName("ExteraConfig")).
    t, n_eng = re.subn(r'[\w.()]+\.isEngineEnabled\(\)',
                       'com.exteragram.messenger.ExteraConfig.getPluginsEngine()',
                       t)
    info(f"  isEngineEnabled rewired: {n_eng} sites (expected 2)")
    if n_eng != 2:
        warn(f"isEngineEnabled sites = {n_eng}, expected 2")

    # 5c. Gate the icon picker OFF: org.telegram.ui.LauncherIconController
    # does not exist in exteraGram (verified 404 on the raw file).
    t, n = delete_lines_with(t, 'actionRow(block, "app_icon"')
    info(f"  app_icon row deleted: {n}")
    if n != 1:
        warn("app_icon row line not found (expected 1)")
    t2, ok = delete_case_block_helper(t, '"app_icon"')
    t = t2
    info(f"  app_icon case deleted: {ok}")
    if not ok:
        warn('case "app_icon": block not found')
    t, ok = delete_method(t, r'^\s*private void pickAppIcon\(\)')
    info(f"  pickAppIcon() deleted: {ok}")
    if not ok:
        warn("pickAppIcon() method not found")
    t, ok = delete_method(t, r'^\s*private static String currentIconTitle\(\)')
    info(f"  currentIconTitle() deleted: {ok}")
    if not ok:
        warn("currentIconTitle() method not found")

    # 5d. Gate the font row off if patch 0023 did not apply fully
    # (without its AndroidUtilities/ApplicationLoader hooks the picker is dead).
    p23 = PATCH_STATS.get("0023-font-selector.patch", (0, 0, []))
    if p23[0] == 0 or p23[1] > 0:
        t, n = delete_lines_with(t, 'actionRow(block, "app_font"')
        info(f"  app_font row gated off (0023 applied={p23[0]} "
             f"skipped={p23[1]}): deleted {n}")
    else:
        info("  0023 applied fully; app_font row kept")

    # 5e. "Based on exteraGram" credit + link in the hub footer
    # (ADDITIONAL_TERMS.md: modified versions must keep the notice + link).
    old1 = '"AMINGRAM · HUB DOSSIER V3 · MADE BY AMIN"'
    new1 = '"AMINGRAM · HUB DOSSIER V3 · MADE BY AMIN · BASED ON EXTERAGRAM"'
    if old1 in t:
        t = t.replace(old1, new1, 1)
        info("  footer credit (no-version) added")
    else:
        warn("footer no-version string not found")
    old2 = ('"AMINGRAM · VERSION " + version + '
            '" · HUB DOSSIER V3 · MADE BY AMIN"')
    new2 = ('"AMINGRAM · VERSION " + version + '
            '" · HUB DOSSIER V3 · MADE BY AMIN · BASED ON EXTERAGRAM"')
    if old2 in t:
        t = t.replace(old2, new2, 1)
        info("  footer credit (version) added")
    else:
        warn("footer version string not found")
    anchor = "            versionView.setText(line);"
    if t.count(anchor) == 1:
        listener = (
            anchor + "\n"
            "            versionView.setOnClickListener(v -> {\n"
            "                try {\n"
            "                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(\"https://github.com/exteraSquad/exteraGram\"));\n"
            "                    getContext().startActivity(intent);\n"
            "                } catch (Exception ignored) {\n"
            "                }\n"
            "            });"
        )
        t = t.replace(anchor, listener, 1)
        info("  footer tap -> exteraGram repo link added")
    else:
        warn(f"versionView.setText anchor count = {t.count(anchor)} (expected 1)")

    write(hub, t)

    # 5f. nekomimi TypefaceHelper: it calls the dead exteraless
    # AppearanceConfig (its own doc says the stand-in "always returns 0",
    # so the 0 branch is the only live one). Replace with the constant 0
    # and an unreachable TITLE_TEXT_CUSTOM value.
    th = SRC / "java/tw/nekomimi/nekogram/helpers/TypefaceHelper.java"
    if not th.is_file():
        fail("TypefaceHelper.java not found in overlay copy")
    else:
        s = read(th)
        reps = [
            ("{@link app.exteraless.appearance.AppearanceConfig#titleText()}",
             "titleText()"),
            ("app.exteraless.appearance.AppearanceConfig.titleText()", "0"),
            ("app.exteraless.appearance.AppearanceConfig.TITLE_TEXT_CUSTOM",
             "4 /* was TITLE_TEXT_CUSTOM; unreachable while titleText is 0 */"),
        ]
        for old, new in reps:
            if old not in s:
                fail(f"TypefaceHelper: anchor not found: {old[:60]}")
            else:
                s = s.replace(old, new, 1)
                info(f"  TypefaceHelper: replaced {old[:50]}...")
        write(th, s)

    # 5g. verify: no exteraless package refs / LauncherIconController left
    # in the amin or nekomimi trees. (Bare "exteraless" in comments is
    # harmless.)
    for root in (SRC / "java/org/telegram/messenger/amin",
                 SRC / "java/tw/nekomimi"):
        for pat in (r"app\.exteraless\.", r"LauncherIconController"):
            hits = grep_count(root, pat)
            if hits:
                fail(f"hub rewire left {pat} in {root.name}: {hits[:5]}")
            else:
                info(f"  verified: no {pat} in {root.name}")


def delete_case_block_helper(text, case_label):
    """Delete `case <label>:` through its break; (inclusive)."""
    lines = text.split("\n")
    for i, ln in enumerate(lines):
        if ln.strip() == f"case {case_label}:":
            j = i
            while j < len(lines):
                if lines[j].strip() == "break;":
                    return "\n".join(lines[:i] + lines[j + 1:]), True
                j += 1
            return text, False
    return text, False


# --------------------------------------------------------------------------
# Step 6: FULL AI delete
# --------------------------------------------------------------------------

AI_PKG = "com.exteragram.messenger.ai."

def step_ai_delete():
    info("== step 6: FULL AI delete ==")
    ai_dir = SRC / "java/com/exteragram/messenger/ai"
    if not ai_dir.is_dir():
        fail("ai package dir not found (expected to delete it)")
    else:
        shutil.rmtree(ai_dir)
        info("  deleted com/exteragram/messenger/ai/")

    # -- TranslateController.java
    p = SRC / "java/org/telegram/messenger/TranslateController.java"
    t = read(p)
    t, n = delete_import_lines(t, [AI_PKG])
    info(f"  TranslateController: removed {n} ai imports")
    t, ok = delete_if_block(t, "TelegramAiReplacement.replacesSummaries")
    info(f"  TranslateController: summarize fallback block deleted: {ok}")
    if not ok:
        fail("TranslateController: TelegramAiReplacement.replacesSummaries "
             "block not found")
    write(p, t)

    # -- TranslateAlert2.java
    p = SRC / "java/org/telegram/ui/Components/TranslateAlert2.java"
    t = read(p)
    t, n = delete_import_lines(t, [AI_PKG])
    info(f"  TranslateAlert2: removed {n} ai imports")
    t, ok = replace_if_else_with_else(t, "TelegramAiReplacement")
    info(f"  TranslateAlert2: AI if/else -> server else-body: {ok}")
    if not ok:
        fail("TranslateAlert2: TelegramAiReplacement if/else not found")
    while True:
        t, ok = delete_if_block(t, "aiReplacementCancel")
        if not ok:
            break
        info("  TranslateAlert2: aiReplacementCancel block deleted")
    # field declaration: a line with aiReplacementCancel, no parens, ends with ;
    lines = t.split("\n")
    kept = []
    n_field = 0
    for ln in lines:
        if ("aiReplacementCancel" in ln and "(" not in ln
                and ln.strip().endswith(";")):
            n_field += 1
            continue
        kept.append(ln)
    t = "\n".join(kept)
    info(f"  TranslateAlert2: aiReplacementCancel field deleted: {n_field}")
    write(p, t)

    # -- AIEditorAlert.java (all anchors verified by direct read)
    p = SRC / "java/org/telegram/ui/Components/AIEditorAlert.java"
    t = read(p)
    t, n = delete_import_lines(t, [AI_PKG])
    info(f"  AIEditorAlert: removed {n} ai imports")
    t, ok = replace_if_else_with_else(t, "TelegramAiReplacement.replacesEditor")
    info(f"  AIEditorAlert: editor if/else -> server request: {ok}")
    if not ok:
        fail("AIEditorAlert: replacesEditor if/else not found")
    old_btn = (
        "        if (errored && aiFallbackFailed) {\n"
        "            button.setText(getString(R.string.TryAgain));\n"
        "            button.setOnClickListener(v -> {\n"
        "                aiFallbackFailed = false;\n"
        "                errored = false;\n"
        "                updateButton();\n"
        "                request();\n"
        "            });\n"
        "        } else if (errored) {"
    )
    if old_btn in t:
        t = t.replace(old_btn, "        if (errored) {", 1)
        info("  AIEditorAlert: TryAgain/aiFallbackFailed branch collapsed")
    else:
        fail("AIEditorAlert: updateButton TryAgain block not found")
    while True:
        t, ok = delete_if_block(t, "aiFallbackCancel")
        if not ok:
            break
        info("  AIEditorAlert: aiFallbackCancel block deleted")
    t, n = delete_lines_with(t, "private boolean aiFallbackFailed;")
    info(f"  AIEditorAlert: aiFallbackFailed field deleted: {n}")
    t, n = delete_lines_with(t, "private Runnable aiFallbackCancel;")
    info(f"  AIEditorAlert: aiFallbackCancel field deleted: {n}")
    t, n = delete_lines_with(t, "aiFallbackCancel = null;")
    info(f"  AIEditorAlert: aiFallbackCancel = null lines deleted: {n}")
    write(p, t)

    # -- VoiceRecognitionController.java (exteraGram moved it to
    # com.exteragram.messenger.speech)
    p = SRC / "java/com/exteragram/messenger/speech/VoiceRecognitionController.java"
    t = read(p)
    t, n = delete_import_lines(t, [AI_PKG])
    info(f"  VoiceRecognitionController: removed {n} ai imports")
    lines = t.split("\n")
    kept = []
    n_field = 0
    for ln in lines:
        if re.search(r'\bClient\s+client\s*;', ln):
            n_field += 1
            continue
        kept.append(ln)
    t = "\n".join(kept)
    info(f"  VoiceRecognitionController: Client field deleted: {n_field}")
    # construction block: `client = new Client.Builder()` ... `.build();`
    lines = t.split("\n")
    out = []
    i = 0
    n_ctor = 0
    while i < len(lines):
        if "new Client.Builder()" in lines[i]:
            j = i
            while j < len(lines) and ".build();" not in lines[j]:
                j += 1
            if j < len(lines):
                n_ctor += 1
                i = j + 1
                continue
            warn("VoiceRecognitionController: Client.Builder block unterminated")
        out.append(lines[i])
        i += 1
    t = "\n".join(out)
    info(f"  VoiceRecognitionController: Client.Builder block deleted: {n_ctor}")
    t, ok = replace_if_else_with_else(t, "AiController")
    if not ok:
        t, ok = replace_if_else_with_else(t, "postprocessingWithAi")
    if not ok:
        t, ok = replace_if_else_with_else(t, "AiConfig")
    if not ok:
        t, ok = replace_if_else_with_else(t, "getTelegramAi")
    info(f"  VoiceRecognitionController: AI if/else -> saveResult: {ok}")
    if not ok:
        fail("VoiceRecognitionController: AI post-processing if/else not found")
    write(p, t)

    # -- ChatActivity.java (AI Generate menu item, text-selection AI hook,
    #    ai Client field/getter)
    p = SRC / "java/org/telegram/ui/ChatActivity.java"
    t = read(p)
    t, n = delete_import_lines(t, [AI_PKG])
    info(f"  ChatActivity: removed {n} ai imports")
    t, n = delete_lines_with(
        t, "private final Client client = new Client.Builder().build();")
    info(f"  ChatActivity: ai Client field deleted: {n}")
    t, ok = delete_method(t, r'^\s*public Client getClient\(\)')
    info(f"  ChatActivity: getClient() deleted: {ok}")
    if not ok:
        fail("ChatActivity: getClient() method not found")
    n_blocks = 0
    while True:
        t, ok = delete_if_block(t, "AiController.canUseAI()")
        if not ok:
            break
        n_blocks += 1
    info(f"  ChatActivity: AiController.canUseAI() if-blocks deleted: {n_blocks}")
    if n_blocks != 2:
        fail(f"ChatActivity: expected 2 AiController if-blocks, deleted {n_blocks}")
    t, ok = delete_case_block(t, "OPTION_GENERATE")
    info(f"  ChatActivity: case OPTION_GENERATE deleted: {ok}")
    if not ok:
        fail("ChatActivity: case OPTION_GENERATE not found")
    t, ok = delete_if_block(t, "visibleDialog instanceof AiResponseAlert")
    info(f"  ChatActivity: visibleDialog AiResponseAlert check deleted: {ok}")
    if not ok:
        fail("ChatActivity: visibleDialog instanceof AiResponseAlert not found")
    # dismissDialogOnPause: drop the AiResponseAlert conjunct (with AI gone
    # the dialog can never be one, so the conjunct is always true).
    if "!(dialog instanceof AiResponseAlert) && " in t:
        t = t.replace("!(dialog instanceof AiResponseAlert) && ", "", 1)
        info("  ChatActivity: dismissDialogOnPause AiResponseAlert conjunct removed")
    elif " && !(dialog instanceof AiResponseAlert)" in t:
        t = t.replace(" && !(dialog instanceof AiResponseAlert)", "", 1)
        info("  ChatActivity: dismissDialogOnPause AiResponseAlert conjunct removed")
    else:
        fail("ChatActivity: dismissDialogOnPause AiResponseAlert conjunct not found")
    write(p, t)

    # -- ChatActivityEnterView.java (send-popup AI hint + ActionRow, both
    # popups; insertAiResponse/buildAiRichInsert helpers). The two
    # `top` booleans that were `!AiController.canUseAI()` are the no-AI
    # value (TranslateBeforeSendWrapper/ActionBarMenuSubItem take
    # (top, bottom); with the AI row gone the translate/schedule button
    # is the top item — the second popup already hardcodes `true`).
    p = SRC / "java/org/telegram/ui/Components/ChatActivityEnterView.java"
    t = read(p)
    t, n = delete_import_lines(t, [AI_PKG])
    info(f"  ChatActivityEnterView: removed {n} ai imports")
    n_chains = 0
    while True:
        t, ok = delete_if_chain(t, "!AiController.canUseAI() && !hidden",
                                exact=True)
        if not ok:
            break
        n_chains += 1
    info(f"  ChatActivityEnterView: AI hint/ActionRow chains deleted: {n_chains}")
    if n_chains != 2:
        fail(f"ChatActivityEnterView: expected 2 AI chains, deleted {n_chains}")
    n_gaps = 0
    while True:
        t, ok = delete_if_block(
            t, "AiController.canUseAI() || !AiController.canUseAI()")
        if not ok:
            break
        n_gaps += 1
    info(f"  ChatActivityEnterView: AI gapView if-blocks deleted: {n_gaps}")
    if n_gaps != 2:
        fail(f"ChatActivityEnterView: expected 2 AI gapView blocks, deleted {n_gaps}")
    t, n = delete_lines_with(
        t, 'final boolean hidden = AiConfig.getPreferences()')
    info(f"  ChatActivityEnterView: ai_hint_hidden declarations deleted: {n}")
    if n != 2:
        fail(f"ChatActivityEnterView: expected 2 hidden declarations, deleted {n}")
    if "!containsText && !AiController.canUseAI()" in t:
        t = t.replace("!containsText && !AiController.canUseAI()",
                      "!containsText", 1)
        info("  ChatActivityEnterView: schedule-button top flag -> !containsText")
    else:
        fail("ChatActivityEnterView: schedule-button top anchor not found")
    if "!AiController.canUseAI()" in t:
        t = t.replace("!AiController.canUseAI()", "true")
        info("  ChatActivityEnterView: translate-button top flag -> true")
    else:
        info("  ChatActivityEnterView: no remaining !AiController.canUseAI()")
    t, ok = delete_method(
        t, r'^\s*private TL_iv\.RichMessage buildAiRichInsert\(')
    info(f"  ChatActivityEnterView: buildAiRichInsert deleted: {ok}")
    if not ok:
        fail("ChatActivityEnterView: buildAiRichInsert not found")
    t, ok = delete_method(
        t, r'^\s*public void insertAiResponse\(String prompt, '
           r'CharSequence response, TL_iv\.RichMessage richMessage\)')
    info(f"  ChatActivityEnterView: insertAiResponse deleted: {ok}")
    if not ok:
        fail("ChatActivityEnterView: insertAiResponse not found")
    write(p, t)

    # -- ChatsPreferencesActivity.kt
    p = SRC / ("java/com/exteragram/messenger/preferences"
               "/ChatsPreferencesActivity.kt")
    if not p.is_file():
        # fallback: find it under src
        found = list(TM.rglob("ChatsPreferencesActivity.kt"))
        p = found[0] if found else p
    t = read(p)
    t, n = delete_import_lines(t, [AI_PKG])
    info(f"  ChatsPreferencesActivity: removed {n} ai imports")
    # Regexes with a negative lookahead so ChatsItem.AI does NOT match the
    # aiFeatures group (ChatsItem.AI_FEATURES), which we KEEP.
    for pattern in (r"ChatsItem\.AI(?![\w])",
                    r"ChatsItem\.GENERATE(?![\w])",
                    r"POST_PROCESSING_WITH_AI"):
        t, n = delete_statement(t, pattern)
        info(f"  ChatsPreferencesActivity: deleted {n} lines for {pattern}")
    write(p, t)

    # -- SettingsRegistry.java
    p = SRC / "java/com/exteragram/messenger/preferences/SettingsRegistry.java"
    if not p.is_file():
        found = list(TM.rglob("SettingsRegistry.java"))
        p = found[0] if found else p
    t = read(p)
    t, n = delete_import_lines(t, [AI_PKG])
    info(f"  SettingsRegistry: removed {n} ai imports")
    t, n = delete_lines_with(t, "AiPreferencesActivity.class")
    info(f"  SettingsRegistry: AiPreferencesActivity map entry deleted: {n}")
    for s in ('"aiTemperature"', '"AI-Service-Reasoning"'):
        t, n = delete_lines_with(t, s)
        info(f"  SettingsRegistry: {s} deleted: {n}")
    # newFeatures is List.of(...) — a METHOD call, where a trailing comma
    # is illegal (unlike an array initializer). Deleting the last two
    # entries leaves "feedBottomTab", as the last element with a comma,
    # which is exactly the run-7 javac error (illegal start of expression
    # at the closing `);`). Strip it.
    if '"feedBottomTab",' in t:
        t = t.replace('"feedBottomTab",', '"feedBottomTab"', 1)
        info("  SettingsRegistry: newFeatures trailing comma removed")
    else:
        fail('SettingsRegistry: "feedBottomTab", not found for comma fix')
    write(p, t)

    # -- PreferencesUtils.java
    p = SRC / "java/com/exteragram/messenger/preferences/PreferencesUtils.java"
    if not p.is_file():
        found = list(TM.rglob("PreferencesUtils.java"))
        p = found[0] if found else p
    t = read(p)
    t, n = delete_import_lines(t, [AI_PKG, "AiConfig", "OnDeviceAvailability"])
    info(f"  PreferencesUtils: removed {n} ai imports")
    # aiConfigKeys array: delete from its declaration through the matching }.
    m = re.search(r'\baiConfigKeys\b', t)
    if m:
        brace = t.find("{", m.start())
        end = brace_span(t, brace) if brace != -1 else None
        if end is not None:
            start = line_start(t, m.start())
            end = line_end(t, end - 1) + 1
            t = t[:start] + t[end:]
            info("  PreferencesUtils: aiConfigKeys array deleted")
        else:
            fail("PreferencesUtils: aiConfigKeys brace match failed")
    else:
        warn("PreferencesUtils: aiConfigKeys not found")
    # AiConfig.CONFIG_NAME element of the configs array: it sits alone on
    # its own line (`AiConfig.CONFIG_NAME,`). Drop exactly that line — a
    # substring delete would also eat the if/case lines that mention the
    # same name (that bug orphaned their bodies in run 5).
    lines = t.split("\n")
    kept = [ln for ln in lines if ln.strip() != "AiConfig.CONFIG_NAME,"]
    n_elem = len(lines) - len(kept)
    t = "\n".join(kept)
    info(f"  PreferencesUtils: AiConfig.CONFIG_NAME array element deleted: {n_elem}")
    if n_elem != 1:
        fail(f"PreferencesUtils: expected 1 CONFIG_NAME array element, deleted {n_elem}")
    t, n = delete_lines_with(t, "AiConfig.getEditor()")
    info(f"  PreferencesUtils: AiConfig.getEditor() line deleted: {n}")
    t, n = delete_lines_with(t, "AiConfig.ensureConfigMigrated();")
    info(f"  PreferencesUtils: AiConfig.ensureConfigMigrated() deleted: {n}")
    if n != 2:
        fail(f"PreferencesUtils: expected 2 ensureConfigMigrated lines, deleted {n}")
    t, ok = delete_if_block(t, "AiConfig.CONFIG_NAME.equals(config)")
    info(f"  PreferencesUtils: AiConfig roles check deleted: {ok}")
    if not ok:
        fail("PreferencesUtils: AiConfig.CONFIG_NAME.equals(config) block not found")
    t, n = delete_lines_with(t, "OnDeviceAvailability.onModelConfigChanged(null);")
    info(f"  PreferencesUtils: OnDeviceAvailability call deleted: {n}")
    if n != 1:
        fail(f"PreferencesUtils: expected 1 OnDeviceAvailability call, deleted {n}")
    t, ok = delete_case_block(t, "AiConfig.CONFIG_NAME")
    info(f"  PreferencesUtils: findBackupItem AiConfig case deleted: {ok}")
    if not ok:
        fail("PreferencesUtils: AiConfig case block not found")
    write(p, t)
    # NOTE: the AI keys left in extraExteraKeys (responseStreaming,
    # temperature, ...) are plain strings in an exclusion list; leaving them
    # is harmless and safer than editing the array.

    # -- verification: no ai-package reference may survive anywhere in src.
    for pat in (r"com\.exteragram\.messenger\.ai\.",
                r"\bAiController\b",
                r"TelegramAiReplacement",
                r"AiPreferencesActivity",
                r"\bAiConfig\b",
                r"AiResponseAlert",
                r"GenerateFromMessageBottomSheet",
                r"EditServiceActivity",
                r"OnDeviceAvailability",
                r"GenerationCallback",
                r"buildAiRichInsert",
                r"insertAiResponse"):
        hits = grep_count(TM / "src", pat)
        if hits:
            fail(f"AI delete left {pat}: {hits[:5]}")
        else:
            info(f"  verified: no {pat} in src")
    # getClient() belonged to the deleted ai Client: no caller may survive
    # in the two files that referenced it.
    for f in (SRC / "java/org/telegram/ui/ChatActivity.java",
              SRC / "java/org/telegram/ui/Components/ChatActivityEnterView.java"):
        txt = read(f)
        found = [ln.strip() for ln in txt.split("\n")
                 if "getClient()" in ln]
        if found:
            fail(f"AI delete left getClient() in {f.name}: {found[:3]}")
        else:
            info(f"  verified: no getClient() in {f.name}")
    for f, pat in (
            (SRC / "java/org/telegram/ui/Components/TranslateAlert2.java",
             r"aiReplacementCancel"),
            (SRC / "java/org/telegram/ui/Components/AIEditorAlert.java",
             r"aiFallbackCancel|aiFallbackFailed")):
        txt = read(f)
        found = [ln.strip() for ln in txt.split("\n")
                 if re.search(pat, ln)]
        if found:
            fail(f"AI delete left {pat} in {f.name}: {found[:3]}")
        else:
            info(f"  verified: no {pat} in {f.name}")

# --------------------------------------------------------------------------
# Step 7: FreeTranslate re-derivation (exteraGram's TranslateController)
# --------------------------------------------------------------------------

FREETRANSLATE_LOOP = """                if (org.telegram.messenger.amin.AminHooks.freeTranslate()) {
                    for (int i = 0; i < pendingTranslation1.messageIds.size(); ++i) {
                        final int id = pendingTranslation1.messageIds.get(i);
                        final Utilities.Callback4<Boolean, Integer, TLRPC.TL_textWithEntities, String> _callback = pendingTranslation1.callbacks.get(i);
                        final String _text = pendingTranslation1.messageTexts.get(i).text;
                        final String toLanguage = pendingTranslation1.language;
                        TranslateAlert2.alternativeTranslate(_text, null, toLanguage, (result, rateLimit) -> {
                            if (result != null) {
                                final TLRPC.TL_textWithEntities resultWithEntities = new TLRPC.TL_textWithEntities();
                                resultWithEntities.text = result;
                                _callback.run(isTranscription, id, resultWithEntities, toLanguage);
                            } else {
                                toggleTranslatingDialog(dialogId, false);
                                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR, getString(rateLimit ? R.string.TranslationFailedAlert1 : R.string.TranslationFailedAlert2));
                            }
                        });
                    }
                    return;
                }

"""


def step_freetranslate():
    info("== step 7: FreeTranslate re-derivation ==")
    p = SRC / "java/org/telegram/messenger/TranslateController.java"
    t = read(p)
    # 7a. isFeatureAvailable(): free translate bypasses the premium unlock.
    anchor = ("    public boolean isFeatureAvailable() {\n"
              "        return TranslationProviders.isChatTranslationUnlocked"
              "(currentAccount) && isChatTranslateEnabled();")
    hook = ("    public boolean isFeatureAvailable() {\n"
            "        if (org.telegram.messenger.amin.AminHooks.freeTranslate()) {\n"
            "            return isChatTranslateEnabled();\n"
            "        }\n"
            "        return TranslationProviders.isChatTranslationUnlocked"
            "(currentAccount) && isChatTranslateEnabled();")
    if "AminHooks.freeTranslate()" in t and "return isChatTranslateEnabled();" in t:
        info("  isFeatureAvailable: hook already present (patch 0003 landed it)")
    elif anchor in t:
        t = t.replace(anchor, hook, 1)
        info("  isFeatureAvailable: freeTranslate early-return added")
    else:
        warn("  isFeatureAvailable: anchor not found; hook NOT added")

    # 7b. isChatTranslateEnabled(): skip the auto-enabled/alternative guard.
    guard_old = ("        if (!getMessagesController().isTranslationsAutoEnabled() "
                 "&& !TranslatorUtils.isAlternativeProvider()) {")
    guard_new = ("        if (!org.telegram.messenger.amin.AminHooks.freeTranslate() "
                 "&& !getMessagesController().isTranslationsAutoEnabled() "
                 "&& !TranslatorUtils.isAlternativeProvider()) {")
    if guard_new in t:
        info("  isChatTranslateEnabled: guard already has freeTranslate")
    elif guard_old in t:
        t = t.replace(guard_old, guard_new, 1)
        info("  isChatTranslateEnabled: freeTranslate added to guard")
    else:
        warn("  isChatTranslateEnabled: guard anchor not found; hook NOT added")

    # 7c. The translate loop, right before the Telegram-server request that
    # follows the commented-out system branch (the `}*/` close). The loop body
    # is copied verbatim from exteraGram's own "alternative" branch in the
    # same method, so it provably compiles in this file.
    if "if (org.telegram.messenger.amin.AminHooks.freeTranslate()) {" in t:
        info("  translate loop: freeTranslate block already present")
    else:
        req = ("                final TLRPC.TL_messages_translateText req = "
               "new TLRPC.TL_messages_translateText();")
        idxs = [m.start() for m in re.finditer(re.escape(req), t)]
        target = None
        for idx in idxs:
            before = t[max(0, idx - 400):idx]
            if "}*/" in before:
                target = idx
                break
        if target is None:
            fail("translate loop: no TL_messages_translateText after a }*/ "
                 "comment close found")
        else:
            ls = line_start(t, target)
            t = t[:ls] + FREETRANSLATE_LOOP + t[ls:]
            info("  translate loop: freeTranslate block inserted before the "
                 "server request")
    write(p, t)
    t = read(p)
    n = t.count("AminHooks.freeTranslate()")
    info(f"  freeTranslate sites in TranslateController: {n} (expected 3)")


# --------------------------------------------------------------------------
# Step 8: speed-boost hooks (best-effort, from the old CI)
# --------------------------------------------------------------------------

def step_speedboost():
    info("== step 8: speed-boost hooks (best-effort) ==")
    flo = SRC / "java/org/telegram/messenger/FileLoadOperation.java"
    t = read(flo)
    target = "maxDownloadRequestsBig = 8;"
    hook = ("maxDownloadRequestsBig = org.telegram.messenger.amin.AminHooks"
            ".downloadBoostMaxRequests(forceSmallChunk) > 0 ? "
            "org.telegram.messenger.amin.AminHooks"
            ".downloadBoostMaxRequests(forceSmallChunk) : 8; "
            "if (org.telegram.messenger.amin.AminHooks"
            ".downloadBoostBigChunkBytes(forceSmallChunk) > 0) { "
            "downloadChunkSizeBig = org.telegram.messenger.amin.AminHooks"
            ".downloadBoostBigChunkBytes(forceSmallChunk); }")
    if "downloadBoostMaxRequests" in t:
        info("  FileLoadOperation: hook already present")
    elif target in t:
        t = t.replace(target, hook, 1)
        write(flo, t)
        info("  FileLoadOperation: download-boost hook added")
    else:
        warn("  FileLoadOperation: anchor 'maxDownloadRequestsBig = 8;' "
             "not found; hub speed-boost picker will not affect downloads")

    fuo = SRC / "java/org/telegram/messenger/FileUploadOperation.java"
    t = read(fuo)
    utarget = "uploadChunkSize = 64 * 1024;"
    uhook = ("uploadChunkSize = org.telegram.messenger.amin.AminHooks"
             ".uploadSpeedBoostEnabled() ? 512 * 1024 : 64 * 1024;")
    if "uploadSpeedBoostEnabled" in t:
        info("  FileUploadOperation: hook already present")
    elif utarget in t:
        t = t.replace(utarget, uhook, 1)
        write(fuo, t)
        info("  FileUploadOperation: upload-boost hook added")
    else:
        warn("  FileUploadOperation: anchor 'uploadChunkSize = 64 * 1024;' "
             "not found; hub upload-boost toggle will not affect uploads")


# --------------------------------------------------------------------------
# Step 9: rebrand strings
# --------------------------------------------------------------------------

def step_rebrand_strings():
    info("== step 9: rebrand strings ==")
    extera_xml = SRC / "res/values/extera.xml"
    if not extera_xml.is_file():
        fail("res/values/extera.xml not found")
    else:
        t = read(extera_xml)
        old = ('<string name="exteraAppName" translatable="false">'
               'exteraGram</string>')
        if old in t:
            t = t.replace(old, old.replace(">exteraGram<", ">AminGram<"), 1)
            info("  exteraAppName -> AminGram")
        else:
            fail("extera.xml: exteraAppName string not found")
        old = ('<string name="Preferences">exteraGram Preferences</string>')
        if old in t:
            t = t.replace(old, old.replace(">exteraGram Preferences<",
                                          ">AminGram Preferences<"), 1)
            info("  Preferences -> AminGram Preferences")
        else:
            fail("extera.xml: Preferences string not found")
        write(extera_xml, t)
    strings_xml = SRC / "res/values/strings.xml"
    t = read(strings_xml)
    if '<string name="AppName">Telegram</string>' in t:
        t = t.replace('<string name="AppName">Telegram</string>',
                      '<string name="AppName">AminGram</string>', 1)
        write(strings_xml, t)
        info("  AppName Telegram -> AminGram")
    else:
        info("  AppName is not 'Telegram' (patch 0004 may have handled it)")


# --------------------------------------------------------------------------
# Step 10: google-services.json fork package
# --------------------------------------------------------------------------

def step_google_services():
    info("== step 10: google-services.json fork package ==")
    p = TM / "google-services.json"
    if not p.is_file():
        fail("exteraGram's TMessagesProj/google-services.json not found")
        return
    data = json.loads(read(p))
    clients = data.get("client", [])
    base = None
    for c in clients:
        pkg = c.get("client_info", {}).get("android_client_info", {}).get(
            "package_name")
        if pkg == "com.exteragram.messenger":
            base = c
            break
    if base is None:
        fail("google-services.json: com.exteragram.messenger client not found")
        return
    have = {c.get("client_info", {}).get("android_client_info", {}).get(
        "package_name") for c in clients}
    for pkg in ("org.telegram.amingram", "org.telegram.amingram.beta"):
        if pkg in have:
            continue
        clone = copy.deepcopy(base)
        clone["client_info"]["android_client_info"]["package_name"] = pkg
        clients.append(clone)
        info(f"  added google-services client {pkg}")
    data["client"] = clients
    write(p, json.dumps(data, indent=2))
    info("  google-services.json clients: " + ", ".join(sorted(
        c["client_info"]["android_client_info"]["package_name"]
        for c in clients)))


# --------------------------------------------------------------------------
# Step 11: missing-strings auto-extract
# --------------------------------------------------------------------------

def step_missing_strings():
    info("== step 11: missing-strings auto-extract ==")
    # Names exteraGram already defines.
    defined = set()
    for xml in (SRC / "res").rglob("*.xml"):
        if "values" not in str(xml):
            continue
        try:
            for m in re.finditer(r'name="([^"]+)"', read(xml)):
                defined.add(m.group(1))
        except Exception:
            pass
    # Names the overlay code references (amin modules + nekomimi helpers +
    # WalletPluginFreeze).
    needed = set()
    amin_dir = SRC / "java/org/telegram/messenger/amin"
    scan_roots = [amin_dir,
                  SRC / "java/tw/nekomimi",
                  SRC / "java/org/telegram/ui/Wallet/WalletPluginFreeze.java"]
    for root in scan_roots:
        files = ([root] if root.is_file() else list(root.rglob("*.java"))
                 if root.exists() else [])
        for jf in files:
            for m in re.finditer(r'R\.string\.(\w+)', read(jf)):
                needed.add(m.group(1))
    missing = sorted(needed - defined)
    if not missing:
        info(f"  all {len(needed)} R.string names used by amin code exist")
        return
    info(f"  {len(missing)} R.string names missing from exteraGram: {missing}")
    overlay_strings = OVERLAY / "src/main/res/values/strings_oe_plugins.xml"
    if not overlay_strings.is_file():
        fail("strings_oe_plugins.xml not found in overlay")
        return
    tree = ET.parse(str(overlay_strings))
    by_name = {}
    for elem in tree.getroot():
        if elem.tag == "string" and "name" in elem.attrib:
            by_name[elem.attrib["name"]] = elem
    not_found = [n for n in missing if n not in by_name]
    if not_found:
        fail(f"strings needed by amin code exist NOWHERE: {not_found}")
        return
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>',
           '    <!-- AminGram: strings referenced by the overlay code that '
           'exteraGram does not define. -->']
    for n in missing:
        elem = by_name[n]
        # OpenExtera is the chat-list title: rebrand it from "exteraless".
        if n == "OpenExtera":
            elem = copy.deepcopy(elem)
            elem.text = "AminGram"
        out.append("    " + ET.tostring(elem, encoding="unicode").strip())
    out.append('</resources>')
    write(SRC / "res/values/amingram_strings.xml", "\n".join(out) + "\n")
    info(f"  wrote res/values/amingram_strings.xml with {len(missing)} strings")


# --------------------------------------------------------------------------
# Step 12: final verification + summary
# --------------------------------------------------------------------------

def check_no_duplicate_cases(path):
    """Fail if any switch in the file has a duplicate case label (javac
    'duplicate case label'). Dumps the region around each duplicate so the
    cause is visible in the rebase log instead of after a 30-min build."""
    t = read(path)
    for m in re.finditer(r'\bswitch\s*\(', t):
        paren = t.find("(", m.start())
        d = 0
        j = paren
        cond_end = None
        while j < len(t):
            if t[j] == "(":
                d += 1
            elif t[j] == ")":
                d -= 1
                if d == 0:
                    cond_end = j
                    break
            j += 1
        if cond_end is None:
            continue
        brace = t.find("{", cond_end)
        if brace == -1:
            continue
        end = brace_span(t, brace)
        if end is None:
            continue
        body = t[brace:end]
        labels = {}
        for lm in re.finditer(r'^\s*case\s+([^:]+):', body, re.M):
            lb = lm.group(1).strip()
            line_no = t[:brace].count("\n") + body[:lm.start()].count("\n") + 1
            if lb in labels:
                lines = t.split("\n")
                lo = max(0, line_no - 12)
                hi = min(len(lines), line_no + 4)
                info(f"  DUP-CASE region in {path.name} around line {line_no}:")
                for k in range(lo, hi):
                    info(f"    {k + 1}: {lines[k]}")
                fail(f"duplicate case label {lb!r} in {path.name} "
                     f"(lines {labels[lb]} and {line_no})")
                return
            labels[lb] = line_no


def step_verify():
    info("== step 12: final verification ==")
    checks = [
        ("exteraless package refs in src", TM / "src", r"app\.exteraless\."),
        ("ai package refs in src", TM / "src",
         r"com\.exteragram\.messenger\.ai\."),
        ("AiController in src", TM / "src", r"\bAiController\b"),
        ("TelegramAiReplacement in src", TM / "src",
         r"TelegramAiReplacement"),
        ("AiPreferencesActivity in src", TM / "src",
         r"AiPreferencesActivity"),
        ("LauncherIconController in amin tree",
         SRC / "java/org/telegram/messenger/amin", r"LauncherIconController"),
    ]
    for label, root, pat in checks:
        hits = grep_count(root, pat)
        if hits:
            fail(f"{label}: {len(hits)} hits, e.g. {hits[:3]}")
        else:
            info(f"  verified: {label}: 0")
    # Duplicate resources across the merged res dirs: build.gradle sets
    # res.srcDirs = [res, res-appicons, res-solar, res-remix, res-tabs] and
    # Gradle fails the build on the same non-values resource in two of
    # them (run 6: ic_launcher_round in res/ vs res-appicons/). Catch it
    # here instead of after a 17-minute build.
    res_dirs = ["res", "res-appicons", "res-solar", "res-remix", "res-tabs"]
    seen = {}
    dups = []
    for d in res_dirs:
        rd = TM / "src/main" / d
        if not rd.is_dir():
            continue
        for f in rd.rglob("*"):
            if not f.is_file():
                continue
            rel = str(f.relative_to(rd))
            if rel.split("/")[0].startswith("values"):
                continue  # values XMLs merge by name; same file OK
            if rel in seen:
                dups.append(f"{rel}: {seen[rel]} vs {d}")
            else:
                seen[rel] = d
    if dups:
        fail(f"duplicate resources across res dirs: {dups[:5]}")
    else:
        info("  verified: no duplicate resources across res dirs")
    # Duplicate case labels (javac fails the whole build on one; run 8).
    for rel in ("java/org/telegram/ui/ChatActivity.java",
                "java/org/telegram/ui/SettingsActivity.java"):
        check_no_duplicate_cases(SRC / rel)
    if FAILURES:
        return
    info("  verified: no duplicate case labels in ChatActivity/SettingsActivity")
    gp = read(EXTERA / "gradle.properties")
    if "APP_PACKAGE=org.telegram.amingram" not in gp:
        fail("gradle.properties: APP_PACKAGE is not org.telegram.amingram")
    if "APP_VERSION_CODE=7304" not in gp:
        fail("gradle.properties: APP_VERSION_CODE is not 7304")
    gs = json.loads(read(TM / "google-services.json"))
    pkgs = {c.get("client_info", {}).get("android_client_info", {}).get(
        "package_name") for c in gs.get("client", [])}
    if "org.telegram.amingram" not in pkgs:
        fail("google-services.json: org.telegram.amingram client missing")
    else:
        info("  verified: gradle.properties + google-services.json")


def main():
    steps = [step_clone, step_gradle_properties, step_overlay, step_patches,
             step_settings_rows, step_hub, step_ai_delete, step_freetranslate,
             step_speedboost, step_rebrand_strings, step_google_services,
             step_missing_strings, step_verify]
    for fn in steps:
        try:
            fn()
        except Exception as e:
            fail(f"{fn.__name__} raised: {e}")
            break
    print("=" * 70)
    print("PATCH SUMMARY")
    for name, (a, s, reasons) in sorted(PATCH_STATS.items()):
        print(f"  {name}: applied={a} skipped={s}")
        for r in reasons:
            print(f"      skip: {r}")
    print(f"WARNINGS: {len(WARNINGS)}")
    for w in WARNINGS:
        print(f"  warn: {w}")
    print(f"FAILURES: {len(FAILURES)}")
    for f in FAILURES:
        print(f"  FAIL: {f}")
    if FAILURES:
        print("REBASE FAILED — fix the failures above and re-run.")
        sys.exit(1)
    print("REBASE OK — extera/ is ready to build.")
    print("  package org.telegram.amingram, versionCode 73049, "
          "task :TMessagesProj:assembleFullAfatRelease")


if __name__ == "__main__":
    main()
