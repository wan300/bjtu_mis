import contextlib
import io
import json
import math
import os
import statistics
import traceback


_ALLOWED_IMPORT_ROOTS = {
    "base64",
    "collections",
    "datetime",
    "decimal",
    "fractions",
    "functools",
    "hashlib",
    "heapq",
    "itertools",
    "json",
    "math",
    "random",
    "re",
    "statistics",
    "string",
}


def _safe_join(root, relative_path):
    text = str(relative_path or "").replace("\\", "/").strip()
    if not text or text.startswith("/") or ".." in text.split("/") or "://" in text:
        raise ValueError("invalid workspace path")
    target = os.path.realpath(os.path.join(root, text))
    root_real = os.path.realpath(root)
    if target != root_real and not target.startswith(root_real + os.sep):
        raise ValueError("path escapes workspace")
    return target


def _limited_import(name, globals=None, locals=None, fromlist=(), level=0):
    root = name.split(".", 1)[0]
    if root not in _ALLOWED_IMPORT_ROOTS:
        raise ImportError(f"import blocked: {name}")
    return __import__(name, globals, locals, fromlist, level)


def _jsonable(value):
    try:
        json.dumps(value, ensure_ascii=False)
        return value
    except TypeError:
        return str(value)


def run_code(code, input_json, workspace_root):
    stdout = io.StringIO()
    stderr = io.StringIO()
    try:
        user_input = json.loads(input_json or "{}")

        def read_text(path):
            target = _safe_join(workspace_root, path)
            with open(target, "r", encoding="utf-8") as handle:
                return handle.read(128 * 1024)

        def write_text(path, content, append=False):
            if not (str(path).startswith("work/") or str(path).startswith("output/")):
                raise ValueError("writes are only allowed under work/ or output/")
            target = _safe_join(workspace_root, path)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            mode = "a" if append else "w"
            with open(target, mode, encoding="utf-8") as handle:
                handle.write(str(content))
            return path

        safe_builtins = {
            "__import__": _limited_import,
            "abs": abs,
            "all": all,
            "any": any,
            "bool": bool,
            "dict": dict,
            "enumerate": enumerate,
            "float": float,
            "int": int,
            "len": len,
            "list": list,
            "max": max,
            "min": min,
            "pow": pow,
            "print": print,
            "range": range,
            "round": round,
            "set": set,
            "sorted": sorted,
            "str": str,
            "sum": sum,
            "tuple": tuple,
            "zip": zip,
        }
        env = {
            "__builtins__": safe_builtins,
            "input": user_input,
            "result": None,
            "read_text": read_text,
            "write_text": write_text,
            "json": json,
            "math": math,
            "statistics": statistics,
        }
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            exec(str(code or ""), env, env)
        return json.dumps(
            {
                "ok": True,
                "result": _jsonable(env.get("result")),
                "stdout": stdout.getvalue()[-65536:],
                "stderr": stderr.getvalue()[-65536:],
            },
            ensure_ascii=False,
        )
    except Exception as exc:
        stderr.write(traceback.format_exc())
        return json.dumps(
            {
                "ok": False,
                "error": str(exc),
                "stdout": stdout.getvalue()[-65536:],
                "stderr": stderr.getvalue()[-65536:],
            },
            ensure_ascii=False,
        )
