from __future__ import annotations

import argparse
import asyncio
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]

from network_recorder import analyze_capture, get_settings, record_mail_traffic, resolve_capture_log


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="BJTU MIS automation capture utilities")
    subparsers = parser.add_subparsers(dest="command", required=True)

    record_mail = subparsers.add_parser(
        "record-mail",
        help="Open the persistent browser and record manual mail-system traffic",
    )
    record_mail.add_argument(
        "--start-url",
        default=None,
        help="Open this URL in a new tab before recording. Defaults to preserving the current browser page.",
    )
    record_mail.add_argument(
        "--open-fallback-url",
        action="store_true",
        help="If no non-blank page exists, navigate a blank page to BJTU_MIS_HOME_URL.",
    )
    record_mail.add_argument(
        "--keep-browser-open",
        action="store_true",
        help="After recording stops, wait for manual browser close instead of closing it automatically.",
    )
    record_mail.add_argument("--label", default="mail", help="Capture directory label under captures/logs")
    record_mail.add_argument(
        "--max-response-bytes",
        type=int,
        default=512 * 1024,
        help="Maximum response body bytes saved per request",
    )
    record_mail.add_argument(
        "--max-request-chars",
        type=int,
        default=128 * 1024,
        help="Maximum request body characters saved per request",
    )

    analyze_mail = subparsers.add_parser(
        "analyze-mail-capture",
        help="Analyze a recorded mail traffic capture and infer API endpoints",
    )
    analyze_mail.add_argument(
        "capture",
        nargs="?",
        help="Path to network.jsonl or a capture directory. Defaults to the latest mail capture.",
    )
    analyze_mail.add_argument(
        "--keyword",
        dest="keywords",
        action="append",
        default=None,
        help="Extra keyword used to identify mail-related requests. May be repeated.",
    )
    return parser


async def run_command(args: argparse.Namespace) -> int:
    settings = get_settings()
    if args.command == "record-mail":
        result = await record_mail_traffic(
            settings,
            start_url=args.start_url,
            label=args.label,
            max_response_bytes=args.max_response_bytes,
            max_request_chars=args.max_request_chars,
            open_fallback_url=args.open_fallback_url,
            close_browser=not args.keep_browser_open,
        )
        print(f"Capture saved: {result.output_dir}")
        print(f"Network log: {result.network_log}")
        print(f"Page structure log: {result.page_structure_log}")
        print(f"Operation log: {result.operation_log}")
        return 0
    if args.command == "analyze-mail-capture":
        network_log = resolve_capture_log(settings, args.capture)
        result = analyze_capture(network_log, keywords=args.keywords)
        print(f"Analyzed: {network_log}")
        print(f"Markdown: {network_log.parent / 'mail_interface_analysis.md'}")
        print(f"Candidate transactions: {result['candidate_transactions']}")
        return 0
    raise ValueError(f"Unknown command: {args.command}")


def main() -> int:
    args = build_parser().parse_args()
    try:
        return asyncio.run(run_command(args))
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
