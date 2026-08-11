#!/usr/bin/env python3
"""Deep check of prototype.html for bugs."""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

FILE = Path(__file__).resolve().parents[1] / "prototype.html"
COMPATIBILITY_TARGET = "frontend/index.html"

def check_js_syntax():
    """Extract JS blocks and check syntax with Node."""
    with open(FILE, "r", encoding="utf-8") as f:
        html = f.read()

    # Extract script blocks
    scripts = re.findall(r"<script[^>]*>(.*?)</script>", html, re.DOTALL)
    errors = []
    for i, s in enumerate(scripts):
        if not s.strip():
            continue
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".js", encoding="utf-8", errors="replace", delete=False
        ) as temporary_file:
            temporary_file.write(s)
            tmp = temporary_file.name
        try:
            result = subprocess.run(
                ["node", "--check", tmp],
                capture_output=True, text=True, timeout=10
            )
        finally:
            try:
                Path(tmp).unlink()
            except FileNotFoundError:
                pass
        if result.returncode != 0:
            errors.append(f"Script block {i}: {result.stderr.strip()}")
        else:
            print(f"  Script block {i}: OK ({len(s)} chars)")
    return errors

def check_duplicate_ids():
    """Check for duplicate HTML element IDs."""
    with open(FILE, "r", encoding="utf-8") as f:
        html = f.read()
    ids = re.findall(r'\bid=["\']([^"\']+)["\']', html)
    dupes = {}
    for id_val in ids:
        dupes[id_val] = dupes.get(id_val, 0) + 1
    return {k: v for k, v in dupes.items() if v > 1}

def check_unmatched_braces():
    """Check for unmatched braces in JS."""
    with open(FILE, "r", encoding="utf-8") as f:
        html = f.read()
    scripts = re.findall(r"<script[^>]*>(.*?)</script>", html, re.DOTALL)
    issues = []
    for i, s in enumerate(scripts):
        if len(s.strip()) < 50:
            continue
        # Simple brace counting (not perfect but catches major issues)
        opens = s.count("{")
        closes = s.count("}")
        if opens != closes:
            issues.append(f"Script {i}: {opens} opens vs {closes} closes (diff={opens-closes})")
        parens_o = s.count("(")
        parens_c = s.count(")")
        if parens_o != parens_c:
            issues.append(f"Script {i}: {parens_o} ( vs {parens_c} ) (diff={parens_o-parens_c})")
        brackets_o = s.count("[")
        brackets_c = s.count("]")
        if brackets_o != brackets_c:
            issues.append(f"Script {i}: {brackets_o} [ vs {brackets_c} ] (diff={brackets_o-brackets_c})")
    return issues

def check_undefined_refs():
    """Check legacy function references or the compatibility redirect."""
    with open(FILE, "r", encoding="utf-8") as f:
        html = f.read()

    if COMPATIBILITY_TARGET in html:
        issues = []
        if not re.search(
            rf'<meta[^>]+http-equiv=["\']refresh["\'][^>]+url={re.escape(COMPATIBILITY_TARGET)}',
            html,
            re.IGNORECASE,
        ):
            issues.append("Compatibility entry is missing its refresh target")
        if f'href="{COMPATIBILITY_TARGET}"' not in html:
            issues.append("Compatibility entry is missing its fallback link")
        if f'location.replace("{COMPATIBILITY_TARGET}")' not in html:
            issues.append("Compatibility entry is missing its JavaScript redirect")
        return issues

    issues = []
    for func in ["showLogin", "hideLogin", "setAuth", "loadServerConversations",
                  "renderAll", "flashStatus", "saveState", "loadState",
                  "escapeHtml", "escapeAttr", "renderMarkdown", "showToast",
                  "updateReviewCard", "trackAchievement", "checkAchievements"]:
        count = len(re.findall(rf"function\s+{func}\b", html))
        if count == 0:
            issues.append(f"Function '{func}' is called but never defined")
        elif count > 1:
            issues.append(f"Function '{func}' defined {count} times")
    return issues

def check_dead_code():
    """Check for common dead code patterns."""
    with open(FILE, "r", encoding="utf-8") as f:
        html = f.read()
    issues = []
    # Check for duplicate event listeners on same element
    # Check for variables assigned but never used (simplified)
    return issues

def check_html_structure():
    """Check for HTML issues."""
    with open(FILE, "r", encoding="utf-8") as f:
        html = f.read()
    issues = []
    # Check for unclosed tags in critical areas
    for tag in ["form", "div", "section", "button"]:
        opens = len(re.findall(rf"<{tag}[\\s>]", html))
        closes = len(re.findall(rf"</{tag}>", html))
        if opens != closes:
            issues.append(f"<{tag}>: {opens} opens vs {closes} closes")
    return issues

def check_css_issues():
    """Check for CSS issues."""
    with open(FILE, "r", encoding="utf-8") as f:
        html = f.read()
    issues = []
    # Check for unclosed braces in style blocks
    styles = re.findall(r"<style[^>]*>(.*?)</style>", html, re.DOTALL)
    for i, s in enumerate(styles):
        opens = s.count("{")
        closes = s.count("}")
        if opens != closes:
            issues.append(f"Style block {i}: {opens} opens vs {closes} closes")
    return issues

def check_event_handler_consistency():
    """Check that event handlers reference existing elements."""
    with open(FILE, "r", encoding="utf-8") as f:
        html = f.read()
    issues = []
    # Check getElementById calls match existing IDs
    get_calls = re.findall(r'getElementById\(["\']([^"\']+)["\']\)', html)
    defined_ids = set(re.findall(r'\bid=["\']([^"\']+)["\']', html))
    for id_val in set(get_calls):
        if id_val not in defined_ids:
            issues.append(f"getElementById('{id_val}') called but element not found in HTML")
    return issues

def check_duplicate_handlers():
    """Check for duplicate document-level event handlers that could conflict."""
    with open(FILE, "r", encoding="utf-8") as f:
        html = f.read()
    issues = []
    # Count submit handlers
    submit_count = len(re.findall(r'document\.addEventListener\(\s*["\']submit["\']', html))
    if submit_count > 1:
        issues.append(f"WARNING: {submit_count} document-level submit handlers - may cause double-firing")
    return issues

if __name__ == "__main__":
    print("=" * 60)
    print(f"DEEP CODE CHECK: {FILE}")
    print("=" * 60)

    print("\n1. JS SYNTAX CHECK")
    print("-" * 40)
    errors = check_js_syntax()
    if errors:
        for e in errors:
            print(f"  ERROR: {e}")
    else:
        print("  All script blocks: syntax OK")

    print("\n2. DUPLICATE HTML IDs")
    print("-" * 40)
    dupes = check_duplicate_ids()
    if dupes:
        for k, v in dupes.items():
            print(f"  ID '{k}' appears {v} times")
    else:
        print("  No duplicate IDs")

    print("\n3. UNMATCHED BRACKETS/BRACES")
    print("-" * 40)
    issues = check_unmatched_braces()
    if issues:
        for i in issues:
            print(f"  {i}")
    else:
        print("  All brackets matched")

    print("\n4. FUNCTION REFS OR COMPATIBILITY REDIRECT")
    print("-" * 40)
    issues = check_undefined_refs()
    if issues:
        for i in issues:
            print(f"  {i}")
    else:
        print("  All required checks passed")

    print("\n5. HTML STRUCTURE")
    print("-" * 40)
    issues = check_html_structure()
    if issues:
        for i in issues:
            print(f"  {i}")
    else:
        print("  HTML tags balanced")

    print("\n6. CSS STRUCTURE")
    print("-" * 40)
    issues = check_css_issues()
    if issues:
        for i in issues:
            print(f"  {i}")
    else:
        print("  CSS braces balanced")

    print("\n7. EVENT HANDLER -> ELEMENT MAPPING")
    print("-" * 40)
    issues = check_event_handler_consistency()
    if issues:
        for i in issues:
            print(f"  {i}")
    else:
        print("  All getElementById targets exist")

    print("\n8. DUPLICATE HANDLERS")
    print("-" * 40)
    issues = check_duplicate_handlers()
    if issues:
        for i in issues:
            print(f"  {i}")
    else:
        print("  No conflicting handlers")

    print("\n" + "=" * 60)
    print("CHECK COMPLETE")
    print("=" * 60)
