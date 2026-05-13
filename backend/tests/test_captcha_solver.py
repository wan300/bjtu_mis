from __future__ import annotations

import pytest

from app.captcha_solver import CaptchaSolveError, calculate_expression, calculate_expression_or_none, decode_ctc


def test_decode_ctc_skips_blank_and_repeated_classes() -> None:
    assert decode_ctc([0, 1, 1, 0, 11, 11, 2, 14], " 0123456789+-*=") == "0+1="


def test_decode_ctc_accepts_charset_without_explicit_blank() -> None:
    assert decode_ctc([1, 2, 14], "0123456789+-*=") == "01="


def test_decode_ctc_treats_legacy_slash_class_as_equals() -> None:
    assert decode_ctc([1, 2, 14], "0123456789+-*/") == "01="


def test_calculate_expression_handles_expected_formats() -> None:
    assert calculate_expression("12+3=") == "15"
    assert calculate_expression("8*7=") == "56"
    assert calculate_expression("15-9=") == "6"


def test_calculate_expression_rejects_missing_equals() -> None:
    with pytest.raises(CaptchaSolveError):
        calculate_expression("12+3")


def test_calculate_expression_rejects_division() -> None:
    with pytest.raises(CaptchaSolveError):
        calculate_expression("12/3=")


def test_calculate_expression_or_none_returns_none_for_invalid_format() -> None:
    assert calculate_expression_or_none("12/3=") is None


def test_calculate_expression_rejects_unsafe_input() -> None:
    with pytest.raises(CaptchaSolveError):
        calculate_expression("__import__('os').system('x')")
