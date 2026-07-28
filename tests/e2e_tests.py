#!/usr/bin/env python3
"""Executable E2E tests and checklist for the Soma CLI (Python >= 3.12).

Build the native image first, then run `soma sync` to ready the tools in XDG_DATA_HOME.

Run from the project root:
    python3 tests/e2e_tests.py
    python3 tests/e2e_tests.py Test02ProjectManagement.test_2_1_add_project_basic
    python3 tests/e2e_tests.py --checklist   # print the checklist without executing commands
"""

import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
from collections.abc import Iterable, Iterator, Sequence
from contextlib import contextmanager, suppress
from dataclasses import dataclass
from itertools import batched, chain
from pathlib import Path
from types import EllipsisType
from typing import Any, ClassVar, Final

type Strs = Iterable[str]

ROOT: Final = Path(__file__).resolve().parents[1]
STATE: Final = ROOT / "target/e2e-tests"
WS: Final = "test-e2e"
CLI: Final = ROOT / "target" / {
    "Darwin": "soma-mac-arm64",
    "Linux": "soma-linux-x64",
    "Windows": "soma-windows-x64.exe",
}[platform.system()]
TIMEOUT: Final = 600
FORMATS: Final = ("json", "csv", "md", "paths")
P_OPTS: Final = ("-p", "--project")
FAIL: Final = ...  # cli(expect=FAIL) asserts any non-zero exit

RES_RE: Final = re.compile(r":\d+\s+@[0-9a-f]+$")  # ":<line> @<docid>" suffix on result paths
NUM_RE: Final = re.compile(r"(\d+): ?(.*)")  # "<n>: <text>" numbered output line
IDX_RE: Final = re.compile(r"Index:\s+\d+ lexical,\s+\d+ chunks,\s+\d+ embeddings,\s+(\d+) vectors")
CUT_RE: Final = re.compile(r"^==> (soma://\S+) <==$", re.M)  # multi-target section header
DOC_RE: Final = re.compile(r"Documents:\s+\d+ total,\s+\d+ ready,\s+(\d+) pending,\s+(\d+) failed")


@dataclass(frozen=True, slots=True)
class Case:
    """One checklist entry, colocated with its suite; cmds/wants are newline-separated."""

    id: str
    title: str
    cmds: str
    wants: str


@dataclass(frozen=True, slots=True)
class Fixture:
    path: str
    name: str
    include: str
    files: tuple[str, ...] = ()

    @property
    def dir(self) -> Path:
        return ROOT / self.path

    def uri(self, file: str = "") -> str:
        return f"soma://{self.name}/{file}"

    def read(self, file: str) -> str:
        return (self.dir / file).read_text(encoding="utf-8")

    def lines(self, file: str) -> list[str]:
        return self.read(file).splitlines()


API, POLICY = "api-design-principles.md", "remote-work-policy.md"
DOCS = Fixture(
    "src/test/resources/fixtures/eval-docs", "e2e-fixture", "**/*.md",
    (API, "distributed-systems-overview.md", "machine-learning-primer.md",
     "product-launch-retrospective.md", POLICY, "startup-fundraising-memo.md"),
)
MEDIA = Fixture(
    "src/test/resources/fixtures/multimedia", "e2e-multimedia", "**/*",
    ("PDF_metadata.pdf", "Dog_morphological_variation.png", "Gettysburg_by_Britton.ogg"),
)
NDOCS: Final = len(DOCS.files)


@dataclass(frozen=True, slots=True)
class CliResult:
    cmd: tuple[str, ...]
    code: int
    out: str
    err: str

    @property
    def text(self) -> str:
        return f"{self.out}\n{self.err}"

    @property
    def json(self) -> Any:
        return json.loads(self.out)


def norm(text: str) -> str:
    """Comparable form: stripped, non-empty lines only."""
    return "\n".join(s for line in text.splitlines() if (s := line.strip()))


class E2E(unittest.TestCase):
    """Base runner: isolated XDG state per test; `Test<N>*` subclasses self-register."""

    cases: ClassVar[tuple[Case, ...]] = ()
    suites: ClassVar[dict[int, type["E2E"]]] = {}

    def __init_subclass__(cls, **kw: Any) -> None:
        super().__init_subclass__(**kw)
        if m := re.fullmatch(r"Test(\d+)\w+", cls.__name__):
            cls.suites[int(m[1])] = cls

    @property
    def e2e_id(self) -> str:
        return ".".join(self._testMethodName.split("_")[1:3])

    def setUp(self) -> None:
        base = STATE / self.e2e_id
        xdg = {f"XDG_{name}_HOME": base / name.lower() for name in ("CONFIG", "STATE")}
        for path in xdg.values():
            path.mkdir(parents=True, exist_ok=True)
        self.env = os.environ | {k: str(v) for k, v in xdg.items()}

    # ---- CLI ----------------------------------------------------------------

    def cli(self, *args: str, expect: int | EllipsisType | None = 0, has: Strs = (),
            has_any: Strs = (), lacks: Strs = (), as_json: bool = False,
            label: str = "", ws: str = WS) -> CliResult:
        cmd = (str(CLI), "--workspace", ws, *args)
        run = subprocess.run(cmd, cwd=ROOT, encoding="utf-8", errors="replace",
                             capture_output=True, timeout=TIMEOUT, env=self.env)
        res = CliResult(cmd, run.returncode, run.stdout, run.stderr)
        msg = (f"{self.e2e_id} {label}".rstrip() + f": {shlex.join(cmd)}\nexit={res.code}\n"
               f"stdout:\n{res.out or '<empty>'}\nstderr:\n{res.err or '<empty>'}")
        if expect is FAIL:
            self.assertNotEqual(res.code, 0, msg)
        elif expect is not None:
            self.assertEqual(res.code, expect, msg)
        self.check(res, has=has, has_any=has_any, lacks=lacks, msg=msg)
        if as_json:
            _ = res.json  # parse or die
        return res

    def add(self, fx: Fixture = DOCS, name: str = "", *extra: str, **kw: Any) -> CliResult:
        return self.cli("project", "add", fx.path, "--name", name or fx.name,
                        "--include", fx.include, *extra, **kw)

    # ---- assertions ----------------------------------------------------------

    def check(self, res: CliResult, *, has: Strs = (), has_any: Strs = (),
              lacks: Strs = (), msg: str = "") -> None:
        out, msg = res.text.casefold(), msg or res.text
        for v in has:
            self.assertIn(v.casefold(), out, msg)
        for v in lacks:
            self.assertNotIn(v.casefold(), out, msg)
        if has_any:
            self.assertTrue(any(v.casefold() in out for v in has_any), msg)

    @staticmethod
    def paths(res: CliResult) -> list[str]:
        return [RES_RE.sub("", s) for line in res.out.splitlines()
                if (s := line.strip()).startswith("soma://")]

    def check_scope(self, res: CliResult, project: str) -> None:
        found = self.paths(res)
        self.assertTrue(found, self.e2e_id)
        self.assertTrue(all(p.startswith(f"soma://{project}/") for p in found), found)

    def check_numbered(self, res: CliResult, want: list[str]) -> None:
        got = [m.groups() for line in res.out.splitlines() if (m := NUM_RE.fullmatch(line))]
        self.assertEqual(got[:len(want)], [(str(i), s) for i, s in enumerate(want, 1)], self.e2e_id)

    def check_formats(self, *args: str) -> None:
        for fmt in FORMATS:
            with self.subTest(fmt=fmt):
                self.cli(*args, "-f", fmt, as_json=fmt == "json", label=f"-f {fmt}")

    def vector_count(self, project: str) -> int:
        res = self.cli("project", "show", project, label="project show")
        self.assertIsNotNone(m := IDX_RE.search(res.out), self.e2e_id)
        return int(m[1])


class DocsE2E(E2E):
    """Shared setUp: the eval-docs fixture is pre-added."""

    def setUp(self) -> None:
        super().setUp()
        self.add()


class Test01GlobalFlags(E2E):
    """Global flags"""

    cases = (
        Case("1.1", "Help", "soma-cli --help\nsoma-cli -h", "Top-level command usage is printed.\nBoth short and long flags behave identically."),
        Case("1.2", "Version", "soma-cli --version", "A version string is printed and the process exits 0."),
    )

    def test_1_1_help(self) -> None:
        full = self.cli("--help", has=("Usage:", "Commands:"))
        self.assertEqual(norm(full.text), norm(self.cli("-h").text))

    def test_1_2_version(self) -> None:
        self.assertRegex(self.cli("--version").text, r"\d+(?:\.\d+){1,3}")


class Test02ProjectManagement(E2E):
    """Project management"""

    cases = (
        Case("2.1", "Add a project (basic)", "soma-cli project add src/test/resources/fixtures/eval-docs --name e2e-fixture --include '**/*.md'", "Project `e2e-fixture` is created and indexed.\nNo duplicate-name error (after the optional reset)."),
        Case("2.2", "Add with inferred name", "soma-cli project add src/test/resources/fixtures/eval-docs\nsoma-cli project remove eval-docs", "A project named `eval-docs` (the directory basename) is added.\nThe temporary `eval-docs` project is removed before the duplicate tests below."),
        Case("2.3", "Duplicate name rejection", "soma-cli project add \"src/test/resources/fixtures/eval-docs\" --name e2e-fixture --include '**/*.md'\nsoma-cli project add \"src/test/resources/fixtures/eval-docs\" --name e2e-fixture --include '**/*.md'", "Error message about a duplicate project name."),
        Case("2.4", "Same path with distinct name", "soma-cli project add \"src/test/resources/fixtures/eval-docs\" --name e2e-fixture --include '**/*.md'\nsoma-cli project add \"src/test/resources/fixtures/eval-docs\" --name another-name --include '**/*.md'", "Both projects are accepted because their names are distinct."),
        Case("2.5", "List projects", "soma-cli project add \"src/test/resources/fixtures/eval-docs\" --name e2e-fixture --include '**/*.md'\nsoma-cli project list", "`e2e-fixture` appears in the list with stats (doc count, etc.)."),
        Case("2.6", "Show project details", "soma-cli project show e2e-fixture", "Detail includes path, include pattern, and document count.\nPath includes `src/test/resources/fixtures/eval-docs`."),
        Case("2.7", "Rename a project", "soma-cli project rename e2e-fixture renamed-fixture\nsoma-cli project list\nsoma-cli project rename renamed-fixture e2e-fixture\nsoma-cli project list", "After rename, `renamed-fixture` appears and `e2e-fixture` does not.\nAfter moving back, `e2e-fixture` is restored."),
        Case("2.8", "Default search membership", "soma-cli project update e2e-fixture --no-default-search\nsoma-cli project list\nsoma-cli project update e2e-fixture --default-search\nsoma-cli project list", "After `--no-default-search`, project shows as excluded/non-default.\nAfter `--default-search`, project shows as included/default."),
        Case("2.9", "Remove a project", "soma-cli project add \"src/test/resources/fixtures/eval-docs\" --name e2e-rm-alias --include '**/remote-work-policy.md'\nsoma-cli project remove e2e-rm-alias\nsoma-cli project list", "Temporary project `e2e-rm-alias` is created.\n`project remove` removes it.\n`e2e-rm-alias` no longer appears in `project list`.\n`e2e-fixture` remains available."),
        Case("2.10", "Generated and vendored directories are skipped", "soma-cli project add <tmpdir> --name e2e-skip --include '**/*.md'\nsoma-cli search \"searchable keep file\" -p e2e-skip -f paths\nsoma-cli search \"node modules skip marker\" -p e2e-skip -f paths\nsoma-cli search \"git skip marker\" -p e2e-skip -f paths\nsoma-cli project remove e2e-skip", "Search for `searchable keep file` returns `docs/keep.md`.\nSearches for markers under `node_modules` and `.git` return no files.\nTemporary project and files are cleaned up."),
    )

    def test_2_1_add_project_basic(self) -> None:
        self.add(has=(f"Added project: {DOCS.name}", f"{NDOCS} ready", "0 failed"))
        self.cli("project", "show", DOCS.name, has=(DOCS.path, DOCS.include, f"{NDOCS} total"))

    def test_2_2_add_with_inferred_name(self) -> None:
        inferred = "eval-docs"
        self.cli("project", "add", DOCS.path, has=(f"Added project: {inferred}", "Scan:"))
        self.cli("project", "show", inferred, has=(inferred, DOCS.path))
        self.cli("project", "remove", inferred)
        self.cli("project", "list", lacks=(inferred,))

    def test_2_3_duplicate_name_rejection(self) -> None:
        self.add()
        self.add(expect=FAIL, has=("Duplicate project",))

    def test_2_4_same_path_with_distinct_name_allowed(self) -> None:
        self.add()
        self.add(name="another-name")
        self.cli("project", "list", has=(DOCS.name, "another-name"))

    def test_2_5_list_projects(self) -> None:
        self.add()
        self.cli("project", "list", has=(DOCS.name,))
        self.cli("project", "show", DOCS.name, has=(f"{NDOCS} total", f"{NDOCS} ready", "Index:"))

    def test_2_6_show_project_details(self) -> None:
        self.add()
        self.cli("project", "show", DOCS.name, has=(DOCS.name, DOCS.path, DOCS.include, f"{NDOCS} total"))

    def test_2_7_rename_project(self) -> None:
        self.add()
        self.cli("project", "rename", DOCS.name, "renamed-fixture")
        self.cli("project", "list", has=("renamed-fixture",), lacks=(DOCS.name,))
        self.cli("project", "rename", "renamed-fixture", DOCS.name)
        self.cli("project", "list", has=(DOCS.name,), lacks=("renamed-fixture",))

    def test_2_8_default_search_membership(self) -> None:
        self.add()
        self.cli("project", "update", DOCS.name, "--no-default-search")
        self.cli("project", "list", has=("Default search scope: no",))
        self.cli("project", "update", DOCS.name, "--default-search")
        self.cli("project", "list", lacks=("Default search scope: no",))

    def test_2_9_remove_project(self) -> None:
        self.add()
        self.cli("project", "add", DOCS.path, "--name", "e2e-rm-alias",
                 "--include", "**/remote-work-policy.md", has=("Added project: e2e-rm-alias",))
        self.cli("project", "remove", "e2e-rm-alias", has=("Removed project: e2e-rm-alias",))
        self.cli("project", "list", has=(DOCS.name,), lacks=("e2e-rm-alias",))

    def test_2_10_generated_vendored_dirs_skipped(self) -> None:
        files = {
            "docs/keep.md": "# Keep\n\nsearchable keep file\n",
            "node_modules/pkg/skip.md": "# Skip\n\nnode modules skip marker\n",
            ".git/skip.md": "# Skip\n\ngit skip marker\n",
        }
        with tempfile.TemporaryDirectory(prefix="soma-e2e-skip-") as tmp:
            for rel, body in files.items():
                path = Path(tmp, rel)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(body, encoding="utf-8")
            self.cli("project", "add", tmp, "--name", "e2e-skip", "--include", "**/*.md")
            self.cli("search", "lexical", "searchable keep file", "-p", "e2e-skip", "-f", "paths",
                     has=("keep.md",))
            for query, folder in (("node modules skip marker", "node_modules"),
                                  ("git skip marker", ".git")):
                with self.subTest(directory=folder):
                    res = self.cli("search", "lexical", query, "-p", "e2e-skip", "-f", "paths")
                    self.check(res, lacks=("skip.md",))
            self.cli("project", "remove", "e2e-skip", expect=None)
            self.cli("project", "list", lacks=("e2e-skip",))


class Test03ContextManagement(DocsE2E):
    """Context management"""

    cases = (
        Case("3.1", "Add global context", "soma-cli context set / \"E2E smoke-test corpus for bundled eval docs\"", "No error. Context attached to the global root."),
        Case("3.2", "Add project-level context", "soma-cli context -p e2e-fixture set / \"Bundled eval docs cover APIs, distributed systems, ML, policy, product, and fundraising\"", "Context attached to the project root."),
        Case("3.3", "Add project file context", "soma-cli context -p e2e-fixture set /api-design-principles.md \"API design notes\"", "Context attached via project-scoped context path."),
        Case("3.4", "Reject context filesystem paths", "soma-cli context set src/test/resources/fixtures/eval-docs \"Filesystem path context for project root\"", "Filesystem path input is rejected; use `-p <project> /path` for project context."),
        Case("3.5", "List all contexts", "soma-cli context set / \"Global context\"\nsoma-cli context -p e2e-fixture set / \"Project context\"\nsoma-cli context -p e2e-fixture set /api-design-principles.md \"File context\"\nsoma-cli context list", "All previously added contexts are listed with their paths and text."),
        Case("3.6", "Remove contexts", "soma-cli context set / \"To be removed\"\nsoma-cli context remove /\nsoma-cli context list", "Removed entries no longer appear in list.\nRemoving project-scoped context uses `-p <project>` and a slash-prefixed path."),
    )

    def test_3_1_add_global_context(self) -> None:
        self.cli("context", "set", "/", "E2E smoke-test corpus for bundled eval docs")
        self.cli("context", "list", has=("/", "E2E smoke-test corpus for bundled eval docs"))

    def test_3_2_add_project_level_context(self) -> None:
        self.cli("context", "-p", DOCS.name, "set", "/",
                 "Bundled eval docs cover APIs, distributed systems, ML, policy, product, and fundraising")
        self.cli("context", "-p", DOCS.name, "list", has=(DOCS.name, "/", "Bundled eval docs cover APIs"))

    def test_3_3_add_project_file_context(self) -> None:
        self.cli("context", "-p", DOCS.name, "set", f"/{API}", "API design notes")
        self.cli("context", "-p", DOCS.name, "list", has=(f"/{API}", "API design notes"))

    def test_3_4_reject_context_filesystem_paths(self) -> None:
        self.cli("context", "set", DOCS.path, "Filesystem path context for project root",
                 expect=FAIL, has=("Context path must start with",))

    def test_3_5_list_all_contexts(self) -> None:
        self.cli("context", "set", "/", "Global context")
        self.cli("context", "-p", DOCS.name, "set", "/", "Project context")
        self.cli("context", "-p", DOCS.name, "set", f"/{API}", "File context")
        self.cli("context", "list",
                 has=("/", "Global context", "Project context", f"/{API}", "File context"))

    def test_3_6_remove_contexts(self) -> None:
        self.cli("context", "set", "/", "To be removed")
        self.cli("context", "-p", DOCS.name, "set", "/", "Project to remove")
        self.cli("context", "-p", DOCS.name, "set", f"/{API}", "File to remove")
        self.cli("context", "remove", "/")
        self.cli("context", "-p", DOCS.name, "remove", "/")
        self.cli("context", "-p", DOCS.name, "remove", f"/{API}")
        self.cli("context", "list", lacks=("to be removed", "project to remove", "file to remove"))


class Test04ProjectFiles(DocsE2E):
    """Project files"""

    cases = (
        Case("4.1", "List all projects", "soma-cli project list", "`e2e-fixture` is listed."),
        Case("4.2", "List files in a project", "soma-cli project files e2e-fixture", "Indexed files: `api-design-principles.md`, `distributed-systems-overview.md`, `machine-learning-primer.md`, `product-launch-retrospective.md`, `remote-work-policy.md`, `startup-fundraising-memo.md`."),
        Case("4.3", "Virtual-path forms", "soma-cli project files e2e-fixture\nsoma-cli project files soma://e2e-fixture/", "Both produce the same file listing as 4.2."),
        Case("4.4", "Project/path prefix forms", "soma-cli project files e2e-fixture/api-design-principles.md\nsoma-cli project files soma://e2e-fixture/api-design-principles.md", "Each command lists documents in `e2e-fixture` matching the exact file path prefix.\n`api-design-principles.md` appears.\nNon-matching files are omitted."),
    )

    def test_4_1_list_all_projects(self) -> None:
        self.cli("project", "list", has=(DOCS.name,))

    def test_4_2_list_files_in_project(self) -> None:
        self.cli("project", "files", DOCS.name, has=DOCS.files)

    def test_4_3_virtual_path_forms(self) -> None:
        plain = self.cli("project", "files", DOCS.name)
        by_uri = self.cli("project", "files", DOCS.uri())
        self.assertEqual(norm(plain.out), norm(by_uri.out))

    def test_4_4_project_path_prefix_forms(self) -> None:
        path = f"{DOCS.name}/{API}"
        for target in (path, f"soma://{path}"):
            with self.subTest(target=target):
                self.cli("project", "files", target, has=(API,), lacks=(POLICY,))


class Test05Get(DocsE2E):
    """Get"""

    cases = (
        Case("5.1", "Get full document by virtual path", "soma-cli get soma://e2e-fixture/api-design-principles.md", "Full document body is printed."),
        Case("5.2", "Reject an unindexed filesystem path", "soma-cli get api-design-principles.md", "The path does not map to an indexed project document and is rejected."),
        Case("5.3", "Get by project/path display path", "soma-cli get e2e-fixture/api-design-principles.md", "Same document body is printed.\nThe display path form is accepted without the `soma://` scheme."),
        Case("5.4", "Get by filesystem path", "soma-cli get src/test/resources/fixtures/eval-docs/api-design-principles.md", "Same document body is printed.\nThe filesystem path is resolved to the indexed `e2e-fixture` document."),
        Case("5.5", "Get rejects line suffix", "soma-cli get soma://e2e-fixture/api-design-principles.md:6", "The suffix is treated as part of the path and is not found."),
        Case("5.6", "Get with --start-line and --max-lines", "soma-cli get soma://e2e-fixture/api-design-principles.md --start-line 6 --max-lines 5", "Exactly 5 lines starting from line 6."),
        Case("5.7", "Get with --line-number", "soma-cli get soma://e2e-fixture/api-design-principles.md --start-line 1 --max-lines 10 --line-number", "Each line prefixed with its line number."),
        Case("5.8", "Get by docid (if known)", "soma-cli search lexical \"the\" -f json\nsoma-cli get \"@<docid>\"", "Document content is returned by docid with the leading `@`."),
        Case("5.9", "Output formats", "soma-cli get soma://e2e-fixture/api-design-principles.md -f json\nsoma-cli get soma://e2e-fixture/api-design-principles.md -f csv\nsoma-cli get soma://e2e-fixture/api-design-principles.md -f md\nsoma-cli get soma://e2e-fixture/api-design-principles.md -f paths", "`-f json` produces valid JSON.\nOther supported formats exit cleanly."),
    )

    uri = DOCS.uri(API)

    def test_5_1_get_full_document_by_virtual_path(self) -> None:
        self.assertEqual(self.cli("get", self.uri).out, DOCS.read(API))

    def test_5_2_rejects_unindexed_filesystem_path(self) -> None:
        self.cli("get", API, expect=FAIL, has=("not found",))

    def test_5_3_get_by_display_path(self) -> None:
        self.assertEqual(self.cli("get", f"{DOCS.name}/{API}").out, DOCS.read(API))

    def test_5_4_get_by_filesystem_path(self) -> None:
        self.assertEqual(self.cli("get", f"{DOCS.path}/{API}").out, DOCS.read(API))

    def test_5_5_get_rejects_line_suffix(self) -> None:
        self.cli("get", f"{self.uri}:6", expect=FAIL, has=(f"{API}:6",))

    def test_5_6_get_with_start_line_and_max_lines(self) -> None:
        res = self.cli("get", self.uri, "--start-line", "6", "--max-lines", "5")
        self.assertEqual(res.out.splitlines(), DOCS.lines(API)[5:10])

    def test_5_7_get_with_line_numbers(self) -> None:
        res = self.cli("get", self.uri, "--start-line", "1", "--max-lines", "10", "--line-number")
        self.check_numbered(res, DOCS.lines(API)[:10])

    def test_5_8_get_by_docid(self) -> None:
        hit = self.cli("search", "lexical", "the", "-f", "json", label="search").json["results"][0]
        docid = "@" + hit["docId"].lstrip("@#")
        self.assertRegex(docid, r"^@[0-9a-f]{6,}$")
        self.assertTrue(self.cli("get", docid).out.strip())

    def test_5_9_output_formats(self) -> None:
        self.check_formats("get", self.uri)


class Test06GetMultipleTargets(DocsE2E):
    """Get multiple targets"""

    cases = (
        Case("6.1", "Multiple targets", "soma-cli get soma://e2e-fixture/api-design-principles.md soma://e2e-fixture/remote-work-policy.md", "Both requested documents are returned."),
        Case("6.2", "Multiple targets with line limit", "soma-cli get soma://e2e-fixture/api-design-principles.md soma://e2e-fixture/remote-work-policy.md --max-lines 3", "Each file shows at most 3 lines."),
        Case("6.3", "--max-size filter", "soma-cli get soma://e2e-fixture/api-design-principles.md --max-size 1024", "Files over 1 KiB are skipped with a note; smaller files are printed."),
        Case("6.4", "Default --max-size behavior", "soma-cli get soma://e2e-fixture/api-design-principles.md", "Files up to the default `10240` bytes (`10 KiB`) are returned.\nLarger files, if any are added to the fixture later, are skipped rather than failing the whole command."),
        Case("6.5", "Output formats for multiple targets", "soma-cli get soma://e2e-fixture/api-design-principles.md soma://e2e-fixture/remote-work-policy.md --max-lines 2 -f json\nsoma-cli get soma://e2e-fixture/api-design-principles.md soma://e2e-fixture/remote-work-policy.md --max-lines 2 -f csv\nsoma-cli get soma://e2e-fixture/api-design-principles.md soma://e2e-fixture/remote-work-policy.md --max-lines 2 -f md\nsoma-cli get soma://e2e-fixture/api-design-principles.md soma://e2e-fixture/remote-work-policy.md --max-lines 2 -f paths", "`-f json` produces valid JSON.\n`-f csv` produces CSV with headers.\n`-f md` produces Markdown-formatted output.\n`-f paths` with line options is rejected."),
    )

    targets = (DOCS.uri(API), DOCS.uri(POLICY))

    def test_6_1_multiple_targets(self) -> None:
        self.cli("get", *self.targets, has=(API, POLICY))

    def test_6_2_multiple_targets_with_line_limit(self) -> None:
        res = self.cli("get", *self.targets, "--max-lines", "3")
        _, *rest = CUT_RE.split(res.out)
        got = {target: body.splitlines()[1:] for target, body in batched(rest, 2)}
        self.assertEqual(set(got), set(self.targets))
        for target, lines in got.items():
            self.assertLessEqual(len(lines), 3, target)

    def test_6_3_max_size_filter(self) -> None:
        res = self.cli("get", self.targets[0], "--max-size", "1024")
        self.assertFalse(res.out.strip())
        self.check(res, has=("Skipped", "content is too large", self.targets[0]))

    def test_6_4_default_max_size(self) -> None:
        res = self.cli("get", self.targets[0])
        self.assertTrue(res.out.strip())
        self.check(res, lacks=("Skipped", "too large"))

    def test_6_5_output_formats_for_multiple_targets(self) -> None:
        for fmt in FORMATS:
            with self.subTest(fmt=fmt):
                res = self.cli("get", *self.targets, "--max-lines", "2", "-f", fmt,
                               expect=FAIL if fmt == "paths" else 0,
                               as_json=fmt == "json", label=f"-f {fmt}")
                match fmt:
                    case "paths":
                        self.check(res, has=("INVALID_REQUEST",))
                    case "json":
                        items = res.json["items"]
                        self.assertEqual(len(items), 2)
                        for item in items:
                            self.assertIn(item.get("virtualPath"), self.targets)
                            self.assertEqual(set(item), {"virtualPath", "body", "context"})
                    case "csv":
                        rows = res.out.splitlines()
                        self.assertGreaterEqual(len(rows), 3)
                        self.assertIn("virtualPath", rows[0])
                        self.check(res, has=self.targets)
                    case _:
                        self.check(res, has=(f"### {t}" for t in self.targets))


class Test07SearchLexical(DocsE2E):
    """Search - lexical"""

    cases = (
        Case("7.1", "Basic lexical search", "soma-cli search lexical \"rate limiting\"\nsoma-cli search l \"rate limiting\"", "Results include `api-design-principles.md`.\nShort subcommand alias `l` behaves like `lexical`."),
        Case("7.2", "Default result count", "soma-cli search lexical \"the\"\nsoma-cli search lexical \"the\" -f json\nsoma-cli search lexical \"the\" -f paths", "Text output exits cleanly.\n`-f json` is parseable JSON.\n`-f paths` exits cleanly."),
        Case("7.3", "With --limit", "soma-cli search lexical \"distributed\" --limit 2", "At most 2 results returned."),
        Case("7.4", "With --no-limit", "soma-cli search lexical \"the\" --no-limit", "All matching documents are returned."),
        Case("7.5", "With --full and --line-number", "soma-cli search lexical \"remote work\" --full --line-number", "Full document body printed with line numbers. `remote-work-policy.md` appears."),
        Case("7.6", "With project filter", "soma-cli search lexical \"API\" -p e2e-fixture\nsoma-cli search lexical \"API\" --project e2e-fixture", "Results are filtered to `e2e-fixture` using short and long option forms."),
        Case("7.7", "Output formats", "soma-cli search lexical \"deployment\" -f json\nsoma-cli search lexical \"deployment\" -f csv\nsoma-cli search lexical \"deployment\" -f md\nsoma-cli search lexical \"deployment\" -f paths", "Each supported format exits cleanly.\n`-f json` is valid JSON."),
    )

    def test_7_1_basic_lexical_search(self) -> None:
        lex = self.cli("search", "lexical", "rate limiting", has=("api-design-principles",))
        self.assertEqual(norm(lex.out), norm(self.cli("search", "l", "rate limiting").out))

    def test_7_2_default_result_count(self) -> None:
        text = self.cli("search", "lexical", "the")
        data = self.cli("search", "lexical", "the", "-f", "json", as_json=True)
        plain = self.cli("search", "lexical", "the", "-f", "paths")
        self.assertLessEqual(
            max(len(self.paths(text)), len(data.json["results"]), len(self.paths(plain))), 20)

    def test_7_3_with_limit(self) -> None:
        res = self.cli("search", "lexical", "distributed", "--limit", "2")
        self.assertLessEqual(len(self.paths(res)), 2)

    def test_7_4_with_no_limit(self) -> None:
        res = self.cli("search", "lexical", "the", "--no-limit")
        self.assertEqual(set(self.paths(res)), {DOCS.uri(f) for f in DOCS.files})

    def test_7_5_with_full_and_line_number(self) -> None:
        res = self.cli("search", "lexical", "remote work", "--full", "--line-number",
                       has_any=("remote-work-policy",))
        self.check_numbered(res, DOCS.lines(POLICY))

    def test_7_6_with_project_filter(self) -> None:
        for opt in P_OPTS:
            res = self.cli("search", "lexical", "API", opt, DOCS.name, has=("api-design-principles",))
            self.check_scope(res, DOCS.name)

    def test_7_7_output_formats(self) -> None:
        self.check_formats("search", "lexical", "deployment")


class Test08SearchVector(DocsE2E):
    """Search - vector"""

    cases = (
        Case("8.1", "Embed first", "soma-cli system embed", "Embeddings generated for all documents in `e2e-fixture`."),
        Case("8.2", "Basic vector search", "soma-cli system embed\nsoma-cli search vector \"consensus algorithms leader election\"\nsoma-cli search v \"consensus algorithms leader election\"", "Results include distributed-systems content.\nShort subcommand alias `v` behaves like `vector`."),
        Case("8.3", "Vector search with --intent", "soma-cli system embed\nsoma-cli search vector \"login failures\" --intent \"auth troubleshooting\"", "`--intent` is accepted."),
        Case("8.4", "Vector search formats", "soma-cli system embed\nsoma-cli search vector \"product launch\" -f json\nsoma-cli search vector \"product launch\" -f paths", "`-f json` is parseable JSON.\n`-f paths` exits cleanly."),
        Case("8.5", "Vector project filter", "soma-cli system embed\nsoma-cli search vector \"APIs\" -p e2e-fixture\nsoma-cli search vector \"APIs\" --project e2e-fixture", "Results are scoped to `e2e-fixture` using both short and long option forms."),
    )

    def test_8_1_embed_first(self) -> None:
        self.cli("system", "embed", has_any=("embed", "vector", "chunk"))
        self.assertGreater(self.vector_count(DOCS.name), 0)

    def test_8_2_basic_vector_search(self) -> None:
        self.cli("system", "embed")
        vec = self.cli("search", "vector", "consensus algorithms leader election",
                       has=("distributed-systems",))
        alias = self.cli("search", "v", "consensus algorithms leader election")
        self.assertEqual(norm(vec.out), norm(alias.out))

    def test_8_3_vector_search_with_intent(self) -> None:
        self.cli("system", "embed")
        res = self.cli("search", "vector", "login failures", "--intent", "auth troubleshooting")
        self.assertTrue(self.paths(res))

    def test_8_4_vector_search_formats(self) -> None:
        self.cli("system", "embed")
        data = self.cli("search", "vector", "product launch", "-f", "json", as_json=True).json
        self.assertIsInstance(data["results"], list)
        self.assertTrue(self.paths(self.cli("search", "vector", "product launch", "-f", "paths")))

    def test_8_5_vector_project_filter(self) -> None:
        self.cli("system", "embed")
        for opt in P_OPTS:
            self.check_scope(self.cli("search", "vector", "APIs", opt, DOCS.name), DOCS.name)


class Test09SearchHybrid(DocsE2E):
    """Search - hybrid"""

    cases = (
        Case("9.1", "Default hybrid search", "soma-cli search \"how should APIs handle validation errors\"\nsoma-cli search hybrid \"how should APIs handle validation errors\"\nsoma-cli search h \"how should APIs handle validation errors\"", "Results include `api-design-principles.md`.\nExplicit `hybrid` and short `h` aliases behave like the default mode."),
        Case("9.2", "Manual hybrid inputs", "soma-cli search hybrid --lex \"leader election\" --vec \"consensus algorithms\" --hyde \"A document explains consensus and leader election\" --intent \"distributed systems\"", "Manual inputs are accepted.\nDistributed-systems results are returned or the command exits cleanly."),
        Case("9.3", "Hybrid with limits", "soma-cli search hybrid \"machine learning\" --limit 2\nsoma-cli search hybrid \"the\" --no-limit", "Both limit modes exit cleanly."),
        Case("9.4", "Hybrid full output", "soma-cli search hybrid \"remote work policy\" --full --line-number", "Full document output includes `remote-work-policy.md`."),
        Case("9.5", "Hybrid project filter", "soma-cli search hybrid \"fundraising\" -p e2e-fixture\nsoma-cli search hybrid \"fundraising\" --project e2e-fixture", "Results are scoped to `e2e-fixture` using both short and long option forms."),
        Case("9.6", "Hybrid output formats", "soma-cli search hybrid \"CAP theorem\" -f json\nsoma-cli search hybrid \"CAP theorem\" -f csv\nsoma-cli search hybrid \"CAP theorem\" -f md\nsoma-cli search hybrid \"CAP theorem\" -f paths", "`-f json` is valid JSON.\nOther supported formats exit cleanly."),
    )

    def setUp(self) -> None:
        super().setUp()
        self.cli("system", "embed")

    def test_9_1_default_hybrid_search(self) -> None:
        query = "how should APIs handle validation errors"
        for args in ((query,), ("hybrid", query), ("h", query)):
            res = self.cli("search", *args, has=("api-design-principles",))
            self.check_scope(res, DOCS.name)

    def test_9_2_manual_hybrid_inputs(self) -> None:
        res = self.cli("search", "hybrid", "--lex", "leader election", "--vec", "consensus algorithms",
                       "--hyde", "A document explains consensus and leader election",
                       "--intent", "distributed systems")
        self.assertTrue(self.paths(res))

    def test_9_3_hybrid_with_limit_and_no_limit(self) -> None:
        limited = self.cli("search", "hybrid", "machine learning", "--limit", "2")
        self.assertLessEqual(len(self.paths(limited)), 2)
        self.assertTrue(self.paths(self.cli("search", "hybrid", "the", "--no-limit")))

    def test_9_4_hybrid_full_line_number(self) -> None:
        res = self.cli("search", "hybrid", "remote work policy", "--full", "--line-number",
                       has_any=("remote-work-policy",))
        self.assertTrue(all(f"{i}: {s}" in res.out for i, s in enumerate(DOCS.lines(POLICY), 1)))

    def test_9_5_hybrid_project_filter(self) -> None:
        for opt in P_OPTS:
            self.check_scope(self.cli("search", "hybrid", "fundraising", opt, DOCS.name), DOCS.name)

    def test_9_6_hybrid_output_formats(self) -> None:
        self.check_formats("search", "hybrid", "CAP theorem")


class Test10IndexingMaintenance(DocsE2E):
    """Indexing and maintenance"""

    cases = (
        Case("10.1", "Status", "soma-cli status", "Shows workspace status, project stats, health warnings, and model info."),
        Case("10.2", "Scan", "soma-cli system scan", "Scans all projects and refreshes the index without error."),
        Case("10.3", "Embed (default)", "soma-cli system embed", "Generates embeddings for un-embedded documents."),
        Case("10.4", "Embed with project filter", "soma-cli system embed -p e2e-fixture\nsoma-cli system embed --project e2e-fixture", "Embeddings are generated or refreshed only for `e2e-fixture` using both short and long option forms.\nOther projects, if present, are not embedded by this command."),
        Case("10.5", "Clean", "soma-cli system clean", "Removes orphaned records from the workspace index.\nDoes not inspect or modify the machine-global processing cache.\nActive documents are not removed."),
        Case("10.6", "Pull models", "soma-cli system pull", "Downloads or confirms GGUF models and Soma-managed external tools are cached."),
    )

    def test_10_1_status(self) -> None:
        self.cli("status", has=("Soma Status", "Workspace", "Host", "Index", "Projects",
                                DOCS.name, "Managed Artifacts", "Health Warnings"))

    def test_10_2_scan(self) -> None:
        self.cli("system", "scan", has=("Scanned", f"{NDOCS} document(s)", f"{NDOCS} ready",
                                        "0 pending", "0 failed"))

    def test_10_3_embed_default(self) -> None:
        self.cli("system", "embed", has_any=("embed", "vector", "chunk"))
        self.assertGreater(self.vector_count(DOCS.name), 0)

    def test_10_4_embed_with_project_filter(self) -> None:
        other = "e2e-embed-other"
        self.add(DOCS, other, "--no-default-search")
        for opt in P_OPTS:
            self.cli("system", "embed", opt, DOCS.name)
            self.assertGreater(self.vector_count(DOCS.name), 0, opt)
            self.assertEqual(self.vector_count(other), 0, opt)

    def test_10_5_clean(self) -> None:
        cache = Path(self.env["XDG_STATE_HOME"]) / "soma/caches/cache.sqlite"

        def stamp() -> tuple[int, int] | None:
            st = cache.stat() if cache.exists() else None
            return st and (st.st_size, st.st_mtime_ns)

        before = stamp()
        self.cli("system", "clean", has_any=("No orphaned", "Cleaned", "orphan"))
        self.cli("project", "show", DOCS.name, has=(f"{NDOCS} total", f"{NDOCS} ready"))
        self.assertEqual(stamp(), before)

    def test_10_6_pull_models(self) -> None:
        self.cli("system", "pull", has_any=("artifact", "cached", "installed", "up to date"))
        self.cli("status", has=("Managed Artifacts",), label="cached artifacts")


class Test11Server(E2E):
    """Server"""

    cases = (
        Case("11.1", "Server help", "soma-cli server --help", "Server subcommand help is printed."),
        Case("11.2", "Server HTTP foreground", "soma-cli server http --port 9191", "Server starts on port 9191.\nHTTP endpoint responds.\nServer stops when killed."),
        Case("11.3", "Server HTTP default subcommand", "soma-cli server --port 9292", "Server starts on port 9292 using the default HTTP mode.\nServer stops when killed."),
        Case("11.4", "Server HTTP default port 8181", "soma-cli server http", "Server starts on the default port 8181 when no --port is specified.\nPort 8181 is listening on localhost.\nServer stops when killed."),
        Case("11.5", "GET /health", "soma-cli server http --port 9393", "GET /health returns JSON with `status` equal to `UP`.\nResponse contains `uptime` as a non-negative integer.\nServer stops when killed."),
        Case("11.6", "POST /api/run status", "soma-cli server http --port 9394", "POST /api/run with command `status` succeeds.\nResponse is the unified RPC envelope with structured status data.\nServer stops when killed."),
        Case("11.7", "POST /api/run search endpoint", "soma-cli server http --port 9399", "POST /api/run with command `search.lexical` returns an RPC envelope.\nThe structured data contains a `results` array.\nServer stops when killed."),
        Case("11.8", "RPC status command", "soma-cli server http --port 9401", "RPC `status` command returns a result with structured data.\nStatus data contains workspace paths, project stats, artifacts, index status, and warnings.\nServer stops when killed."),
        Case("11.9", "RPC search command", "soma-cli server http --port 9402", "RPC `search.lexical` call returns a result.\nResult `data.results` is a list.\nResult items expose search result fields.\nServer stops when killed."),
    )

    def test_11_1_server_help(self) -> None:
        self.cli("server", "--help", has_any=("server", "usage", "help"))

    def test_11_2_server_http_foreground(self) -> None:
        with self.serve(9191) as port:
            self.assertEqual(self._health(port)["status"], "UP")

    def test_11_3_server_http_default_subcommand(self) -> None:
        with self.serve(9292, http=False) as port:
            self.assertEqual(self._health(port)["status"], "UP")

    def test_11_4_server_http_default_port(self) -> None:
        with self.serve(8181, port_flag=False) as port:
            self.assertEqual(self._health(port)["status"], "UP")

    def test_11_5_health_endpoint(self) -> None:
        with self.serve(9393) as port:
            health = self._health(port)
            self.assertEqual(health["status"], "UP")
            self.assertIsInstance(health["uptime"], int)
            self.assertGreaterEqual(health["uptime"], 0)

    def test_11_6_post_api_run_status(self) -> None:
        with self.serve(9394) as port:
            self.assertIn("workspace", self.rpc(port, "status"))

    def test_11_7_post_api_run_search(self) -> None:
        with self.serve(9399) as port:
            data = self.rpc(port, "search.lexical", ["test"], {"limit": 5, "format": "json"})
            self.assertIsInstance(data["results"], list)

    def test_11_8_rpc_status(self) -> None:
        self.add()
        with self.serve(9401) as port:
            data = self.rpc(port, "status")
            self.assertLessEqual({"workspace", "configFile", "databaseFile", "projects",
                                  "artifacts", "indexStatus", "warnings"}, set(data))
            proj = next(p for p in data["projects"] if p["name"] == DOCS.name)
            self.assertIsInstance(proj["root"], str)
            self.assertEqual(proj["stats"]["documents"], NDOCS)

    def test_11_9_rpc_search(self) -> None:
        self.add()
        with self.serve(9402) as port:
            results = self.rpc(port, "search.lexical", ["API"], {"limit": 5, "format": "json"})["results"]
            self.assertTrue(results)
            for hit in results:
                self.assertLessEqual({"docId", "virtualPath", "title", "score", "snippet"}, set(hit))

    # ---- plumbing -----------------------------------------------------------

    @contextmanager
    def serve(self, port: int, *, http: bool = True, port_flag: bool = True) -> Iterator[int]:
        args = ["server"] + ["http"] * http + ["--port", str(port)] * port_flag
        proc = subprocess.Popen([str(CLI), "--workspace", WS, *args], cwd=ROOT, env=self.env,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            self._await_up(port)
            yield port
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()

    def _await_up(self, port: int, tries: int = 75) -> None:
        for _ in range(tries):
            with suppress(OSError):
                self._health(port)
                return
            time.sleep(0.2)
        self.fail(f"server :{port} never came up")

    @staticmethod
    def _health(port: int) -> dict[str, Any]:
        with urllib.request.urlopen(f"http://localhost:{port}/health", timeout=10) as resp:
            return json.load(resp)

    def rpc(self, port: int, cmd: str, args: Sequence[str] = (),
            opts: dict[str, Any] | None = None) -> dict[str, Any]:
        body = json.dumps({"command": cmd, "args": [*args], "options": opts or {}, "global": {}})
        req = urllib.request.Request(f"http://localhost:{port}/api/run", body.encode(),
                                     {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            reply = json.load(resp)
        self.assertLessEqual({"success", "exitCode", "stdout", "stderr", "data"}, set(reply))
        self.assertTrue(reply["success"])
        self.assertEqual(reply["exitCode"], 0)
        self.assertIsInstance(reply["data"], dict)
        return reply["data"]


class Test12SearchQualityRegression(E2E):
    """Search quality regression"""

    cases = (
        Case("12.1", "Search fixture regression", "soma-cli system embed -p eval-docs\nsoma-cli search <mode> \"<fixture query>\" -p test-eval-docs --limit <top-k> --format paths", "Every expected file appears within the query's configured top K results."),
    )

    SPEC: ClassVar[Path] = ROOT / "src/test/resources/search-regression.json"

    def setUp(self) -> None:
        super().setUp()
        self.spec = json.loads(self.SPEC.read_text(encoding="utf-8"))
        self.proj = self.spec["project"]
        self.cli("project", "add", str(self.SPEC.parent / self.spec["root"]), "--name", self.proj,
                 *chain.from_iterable(("--include", inc) for inc in self.spec["include"]))

    def test_12_1_fixture_queries_find_expected_files(self) -> None:
        self.cli("system", "embed", "-p", self.proj)
        for q in self.spec["queries"]:
            with self.subTest(mode=q["mode"], query=q["query"]):
                res = self.cli("search", q["mode"], q["query"], "-p", self.proj,
                               "--limit", str(q["top_k"]), "-f", "paths")
                want = {f"soma://{self.proj}/{p}" for p in q["expected_paths"]}
                self.assertLessEqual(want, set(res.out.splitlines()), res.out)


class Test13EdgeCases(E2E):
    """Edge cases and error handling"""

    cases = (
        Case("13.1", "Unknown command", "soma-cli nonexistent-command", "Error message indicating unknown command."),
        Case("13.2", "Missing required arguments", "soma-cli project add --name\nsoma-cli get\nsoma-cli search", "Each prints a usage/error message."),
        Case("13.3", "Get nonexistent document", "soma-cli get soma://e2e-fixture/does-not-exist.md", "Error or empty result indicating document not found."),
        Case("13.4", "Remove nonexistent project", "soma-cli project remove nonexistent-project", "Error message about project not found."),
        Case("13.5", "Search with no indexed data", "soma-cli project remove e2e-fixture\nsoma-cli search \"anything\"\nsoma-cli project add \"src/test/resources/fixtures/eval-docs\" --name e2e-fixture --include '**/*.md'", "No results or a message indicating no projects are indexed.\nRe-add the project for subsequent tests."),
    )

    def test_13_1_unknown_command(self) -> None:
        self.cli("nonexistent-command", expect=FAIL, has=("Unmatched argument",))

    def test_13_2_missing_required_arguments(self) -> None:
        for want, args in (("Usage:", ("project", "add", "--name")),
                           ("Usage:", ("get",)),
                           ("Hybrid search requires", ("search",))):
            with self.subTest(args=args):
                self.cli(*args, expect=FAIL, has=(want,))

    def test_13_3_get_nonexistent_document(self) -> None:
        self.add()
        self.cli("get", DOCS.uri("does-not-exist.md"), expect=FAIL, has=("Document not found",))

    def test_13_4_remove_nonexistent_project(self) -> None:
        self.cli("project", "remove", "nonexistent-project", expect=FAIL, has=("not found",))

    def test_13_5_search_with_no_indexed_data(self) -> None:
        self.cli("project", "remove", DOCS.name, expect=None)
        self.cli("search", "anything", expect=FAIL, has=("Vector index is empty",))
        self.add(has=(f"Added project: {DOCS.name}", f"{NDOCS} ready"))


class Test14WorkspaceIsolation(DocsE2E):
    """--workspace flag isolation"""

    cases = (
        Case("14.1", "Verify workspace isolation", "soma-cli --workspace test-other project add \"src/test/resources/fixtures/eval-docs\" --name other-fixture --include '**/*.md'\nsoma-cli --workspace test-other project list\nsoma-cli project list\nsoma-cli --workspace test-other project remove other-fixture", "`other-fixture` appears only in `test-other` workspace.\n`e2e-fixture` appears only in `test-e2e` workspace.\nWorkspaces are fully isolated."),
    )

    def test_14_1_verify_index_isolation(self) -> None:
        other, other_ws = "other-fixture", "test-other"
        self.cli("project", "add", DOCS.path, "--name", other, "--include", DOCS.include, ws=other_ws)
        self.cli("project", "list", has=(other,), lacks=(DOCS.name,), ws=other_ws)
        self.cli("project", "list", has=(DOCS.name,), lacks=(other,))
        self.cli("project", "remove", other, ws=other_ws)


class Test15OutputFormatConsistency(DocsE2E):
    """Output format consistency"""

    cases = (
        Case("15.1", "Stderr vs stdout separation", "soma-cli search lexical \"APIs\" -f json", "First: valid JSON on stdout (parseable by `json.tool`).\nSecond: stderr remains separate from stdout."),
    )

    def test_15_1_stderr_vs_stdout_separation(self) -> None:
        res = self.cli("search", "lexical", "APIs", "-f", "json", as_json=True)
        self.assertTrue(res.out.strip().startswith("{"))
        for noise in ("Preparing index", "Updating index"):
            self.assertNotIn(noise, res.out)
        if err := res.err.strip():
            self.assertNotIn(err, res.out)


class Test16MultimodalIndexing(DocsE2E):
    """Multimodal indexing and extraction"""

    cases = (
        Case("16.1", "Add multimodal fixture project with wildcard include", "soma-cli project add \"src/test/resources/fixtures/multimedia\" --name e2e-multimedia --include \"**/*\"\nsoma-cli project show e2e-multimedia", "Project `e2e-multimedia` is created and indexed.\n`project show` displays path `src/test/resources/fixtures/multimedia` and include `**/*`.\nRich files such as PDF, image, and audio are marked for extraction rather than being fully extracted by `project add`."),
        Case("16.2", "List multimodal files", "soma-cli project add \"src/test/resources/fixtures/multimedia\" --name e2e-multimedia --include \"**/*\"\nsoma-cli project files e2e-multimedia\nsoma-cli project files soma://e2e-multimedia/", "Both commands list the same three canonical URI files:\n`PDF_metadata.pdf`\n`Dog_morphological_variation.png`\n`Gettysburg_by_Britton.ogg`"),
        Case("16.3", "Read pending PDF before extraction", "soma-cli project add \"src/test/resources/fixtures/multimedia\" --name e2e-multimedia --include \"**/*\"\nsoma-cli get soma://e2e-multimedia/PDF_metadata.pdf --start-line 1 --max-lines 20", "The command reports that `PDF_metadata.pdf` is pending extraction.\nPending rich documents do not expose a ready searchable body."),
        Case("16.4", "Verify image and audio pending before extraction", "soma-cli project add \"src/test/resources/fixtures/multimedia\" --name e2e-multimedia --include \"**/*\"\nsoma-cli get soma://e2e-multimedia/Dog_morphological_variation.png\nsoma-cli get soma://e2e-multimedia/Gettysburg_by_Britton.ogg\nsoma-cli status", "The image document is reported as pending extraction and has no ready body.\nThe audio document is reported as pending extraction and has no ready body.\n`status` reports at least 2 pending extractions and suggests running `soma sync`."),
        Case("16.5", "Run explicit multimodal extraction", "soma-cli project add \"src/test/resources/fixtures/multimedia\" --name e2e-multimedia --include \"**/*\"\nsoma-cli system extract\nsoma-cli status", "`system extract` prints progress for pending image/audio documents and a summary like `Processed 2 document(s): ...`.\nIf the optional local vision/OCR/audio tools are available, the image and/or audio documents move from pending to extracted.\nIf any optional tools are unavailable, the command fails those media documents gracefully; text/PDF indexing remains usable.\n`status` updates pending and failed extraction counts accordingly."),
        Case("16.6", "Inspect extracted image content", "soma-cli project add \"src/test/resources/fixtures/multimedia\" --name e2e-multimedia --include \"**/*\"\nsoma-cli system extract\nsoma-cli get soma://e2e-multimedia/Dog_morphological_variation.png --start-line 1 --max-lines 30 --max-size 3MiB", "Image extraction succeeds and output includes the generated visual/OCR text.\n`--max-size 3MiB` allows `get` to return the extracted body for this source file."),
        Case("16.7", "Inspect extracted audio content", "soma-cli project add \"src/test/resources/fixtures/multimedia\" --name e2e-multimedia --include \"**/*\"\nsoma-cli system extract\nsoma-cli get soma://e2e-multimedia/Gettysburg_by_Britton.ogg --start-line 1 --max-lines 30 --max-size 3MiB", "Audio extraction succeeds and output contains the transcript text for `Gettysburg_by_Britton.ogg`.\n`--max-size 3MiB` allows `get` to return the extracted body for this source file."),
        Case("16.8", "Search extracted multimodal text", "soma-cli project add \"src/test/resources/fixtures/multimedia\" --name e2e-multimedia --include \"**/*\"\nsoma-cli system extract\nsoma-cli search \"dog\" -p e2e-multimedia\nsoma-cli search \"Gettysburg\" -p e2e-multimedia", "Search results for image/audio content appear only when the corresponding extraction succeeded and produced matching text.\nFailed or still-pending media documents should not be treated as successfully extracted searchable content.\nThe commands exit cleanly even when optional extraction tools are missing."),
        Case("16.9", "Embed extracted multimodal text", "soma-cli project add \"src/test/resources/fixtures/multimedia\" --name e2e-multimedia --include \"**/*\"\nsoma-cli system extract\nsoma-cli system embed -p e2e-multimedia\nsoma-cli search vector \"dog morphological variation\" -p e2e-multimedia\nsoma-cli search vector \"Gettysburg speech\" -p e2e-multimedia", "Embedding skips pending/failed extraction placeholders and embeds only ready or extracted text.\nPDF text can be embedded when PDF extraction succeeded.\nImage/audio results appear in vector search only when their extraction succeeded."),
        Case("16.10", "Cleanup multimodal fixture project", "soma-cli project add \"src/test/resources/fixtures/multimedia\" --name e2e-multimedia --include \"**/*\"\nsoma-cli project remove e2e-multimedia\nsoma-cli project list", "`e2e-multimedia` is removed.\n`e2e-fixture` remains available for the rest of this e2e test run.\nBundled fixture files under `src/test/resources/fixtures/multimedia/` are left untouched."),
    )

    PDF, PNG, OGG = MEDIA.files

    def setUp(self) -> None:
        super().setUp()
        if self.e2e_id != "16.1":
            self.add(MEDIA)

    def extract(self) -> CliResult:
        return self.cli("system", "extract", expect=None)

    def counts(self) -> tuple[int, int]:
        """(pending, failed) parsed from `status`."""
        res = self.cli("status", has=("Documents:",))
        self.assertIsNotNone(m := DOC_RE.search(res.out), res.out)
        return int(m[1]), int(m[2])

    def test_16_1_add_multimodal_project(self) -> None:
        self.add(MEDIA, has=(f"Added project: {MEDIA.name}", "pending"))
        self.cli("project", "show", MEDIA.name,
                 has=(MEDIA.path, MEDIA.include, "Documents:", "pending"))

    def test_16_2_list_multimodal_files(self) -> None:
        plain = self.cli("project", "files", MEDIA.name, has=MEDIA.files)
        by_uri = self.cli("project", "files", MEDIA.uri(), has=MEDIA.files)
        self.assertEqual(norm(plain.out), norm(by_uri.out))

    def test_16_3_read_indexed_pdf_text(self) -> None:
        res = self.cli("get", MEDIA.uri(self.PDF), "--start-line", "1", "--max-lines", "20",
                       expect=FAIL, has=("pending", "soma sync"))
        self.assertFalse(res.out.strip())

    def test_16_4_verify_pending_placeholders(self) -> None:
        for name in (self.PNG, self.OGG):
            res = self.cli("get", MEDIA.uri(name), expect=FAIL,
                           has=("pending", Path(name).stem, "soma sync"))
            self.assertFalse(res.out.strip())
        self.assertGreaterEqual(self.counts()[0], 2)

    def test_16_5_run_explicit_extraction(self) -> None:
        before = self.counts()
        res = self.extract()
        self.assertIn(res.code, (0, 1))
        self.check(res, has_any=("Processed", "No pending rich/media extractions"))
        after = self.counts()
        self.assertLessEqual(sum(after), 4)
        self.assertNotEqual(after, before)

    def test_16_6_inspect_extracted_image(self) -> None:
        self.extract()
        self.cli("get", MEDIA.uri(self.PNG), "--start-line", "1", "--max-lines", "30",
                 "--max-size", "3MiB", has=("dog",), lacks=("[SKIPPED:",))

    def test_16_7_inspect_extracted_audio(self) -> None:
        self.extract()
        res = self.cli("get", MEDIA.uri(self.OGG), "--start-line", "1", "--max-lines", "30",
                       "--max-size", "3MiB", lacks=("[SKIPPED:", "pending", "failed", "not ready"))
        self.assertTrue(res.out.strip())

    def test_16_8_search_extracted_multimodal(self) -> None:
        self.extract()
        for query, name in (("dog", self.PNG), ("Gettysburg", self.OGG)):
            res = self.cli("search", query, "-p", MEDIA.name, expect=None)
            self.assertIn(res.code, (0, 1))
            if res.code == 0:
                self.check(res, has_any=(MEDIA.uri(name), Path(name).stem))
            else:
                self.check(res, lacks=(MEDIA.uri(name),))

    def test_16_9_embed_extracted_multimodal(self) -> None:
        self.extract()
        self.cli("system", "embed", "-p", MEDIA.name, has_any=("embed", "vector", "chunk", "No"))
        vectors = self.vector_count(MEDIA.name)
        dog = self.cli("search", "vector", "dog morphological variation", "-p", MEDIA.name, expect=None)
        getty = self.cli("search", "vector", "Gettysburg speech", "-p", MEDIA.name, expect=None)
        if vectors:
            self.assertEqual((dog.code, getty.code), (0, 0))
        else:
            for res, name in ((dog, self.PNG), (getty, self.OGG)):
                self.assertIn(res.code, (0, 1))
                self.check(res, lacks=(MEDIA.uri(name),))

    def test_16_10_cleanup_multimodal_project(self) -> None:
        self.cli("project", "remove", MEDIA.name)
        self.cli("project", "list", has=(DOCS.name,), lacks=(MEDIA.name,))
        self.assertTrue(all((MEDIA.dir / name).exists() for name in MEDIA.files))


def verify_cases() -> None:
    """SSOT guard: checklist case ids must exactly match test method ids per suite."""
    loader = unittest.TestLoader()
    for gid, suite in E2E.suites.items():
        assert suite.cases and suite.__doc__, f"Workflow {gid}: missing cases or title docstring"
        want = {c.id for c in suite.cases}
        got = {".".join(n.split("_")[1:3]) for n in loader.getTestCaseNames(suite)}
        assert want == got, f"Workflow {gid}: {want ^ got}"


def render(cmd: str) -> str:
    """Rewrite a checklist `soma-cli ...` line to the real binary plus workspace."""
    argv = shlex.split(cmd)
    if argv[:1] != ["soma-cli"]:
        raise ValueError(f"Invalid checklist command: {cmd}")
    ws_opts = [a for a in argv if a in ("-w", "--workspace") or a.startswith(("-w=", "--workspace="))]
    if len(ws_opts) > 1:
        raise ValueError(f"Multiple workspace options: {cmd}")
    argv[0] = str(CLI)
    if not ws_opts:
        argv[1:1] = ["--workspace", WS]
    return shlex.join(argv)


def print_checklist() -> None:
    print("# Full E2E CLI Test Cases\n\nExecutable checklist in tests/e2e_tests.py\n")
    for gid, suite in sorted(E2E.suites.items()):
        print(f"## {gid}. {suite.__doc__}\n")
        for case in suite.cases:
            print(f"### {case.id} {case.title}\n\nCommands:")
            print(*(f"  {render(c)}" for c in case.cmds.splitlines()), sep="\n")
            print("\nExpected:")
            print(*(f"- [{case.id}] {w}" for w in case.wants.splitlines()), sep="\n")
            print()


def main() -> int:
    verify_cases()
    if "--checklist" in sys.argv:
        print_checklist()
        return 0
    shutil.rmtree(STATE, ignore_errors=True)
    run = unittest.main(verbosity=2, exit=False)
    return 0 if run.result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
