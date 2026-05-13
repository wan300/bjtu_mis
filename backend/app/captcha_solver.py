from __future__ import annotations

import re
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Iterable

from .config import Settings

CAPTCHA_EXPRESSION_RE = re.compile(r"^(\d+)([+\-*])(\d+)=$")
MAX_DECODE_STEPS = 8
MODEL_WIDTH = 130
MODEL_HEIGHT = 42


class CaptchaSolveError(RuntimeError):
    pass


@dataclass(frozen=True)
class CaptchaSolveResult:
    expression: str
    answer: str


def _model_charset(charset: str, blank_index: int) -> str:
    normalized = charset.replace("/", "=") if "=" not in charset and "/" in charset else charset
    if blank_index == 0 and (not normalized or not normalized[0].isspace()):
        return f" {normalized}"
    return normalized


def decode_ctc(indices: Iterable[int], charset: str, blank_index: int = 0) -> str:
    model_charset = _model_charset(charset, blank_index)
    chars: list[str] = []
    previous: int | None = None
    for raw_index in indices:
        index = int(raw_index)
        if index == blank_index:
            previous = index
            continue
        if index == previous:
            continue
        if index < 0 or index >= len(model_charset):
            raise CaptchaSolveError(f"验证码识别输出包含未知类别: {index}")
        char = model_charset[index]
        if char.isspace():
            previous = index
            continue
        chars.append(char)
        previous = index
    return "".join(chars)


def calculate_expression(expression: str) -> str:
    answer = calculate_expression_or_none(expression)
    if answer is None:
        raise CaptchaSolveError(f"验证码算式格式不匹配: {expression}")
    return answer


def calculate_expression_or_none(expression: str) -> str | None:
    normalized = "".join(expression.split())
    match = CAPTCHA_EXPRESSION_RE.fullmatch(normalized)
    if match is None:
        return None

    left = int(match.group(1))
    operator = match.group(2)
    right = int(match.group(3))
    if operator == "+":
        return str(left + right)
    if operator == "-":
        return str(left - right)
    return str(left * right)


class CaptchaSolver:
    def __init__(self, settings: Settings) -> None:
        self.model_path = Path(settings.captcha_model_path)
        self.charset = settings.captcha_charset
        self._model = None

    def solve(self, image_bytes: bytes) -> CaptchaSolveResult:
        expression = self.recognize(image_bytes)
        answer = calculate_expression(expression)
        return CaptchaSolveResult(expression=expression, answer=answer)

    def recognize(self, image_bytes: bytes) -> str:
        if not self.model_path.exists():
            raise CaptchaSolveError(f"验证码模型不存在: {self.model_path}")

        try:
            import numpy as np
            import torch
            from PIL import Image
        except ImportError as exc:
            raise CaptchaSolveError("验证码自动识别依赖 torch/numpy/Pillow 未安装。") from exc

        if self._model is None:
            self._model = torch.jit.load(str(self.model_path), map_location="cpu")
            self._model.eval()

        try:
            image = Image.open(BytesIO(image_bytes)).convert("RGB").resize((MODEL_WIDTH, MODEL_HEIGHT))
        except Exception as exc:
            raise CaptchaSolveError("验证码图片无法读取。") from exc

        array = np.asarray(image, dtype=np.float32) / 255.0
        array = np.transpose(array, (2, 0, 1))[None, ...]
        tensor = torch.from_numpy(array)
        with torch.no_grad():
            output = self._model(tensor)
        if isinstance(output, (tuple, list)):
            output = output[0]
        if output.ndim != 3:
            raise CaptchaSolveError(f"验证码模型输出维度异常: {tuple(output.shape)}")

        # CRNN TorchScript traces usually return [time, batch, classes].
        if output.shape[1] == 1:
            logits = output[:, 0, :]
        elif output.shape[0] == 1:
            logits = output[0, :, :]
        else:
            raise CaptchaSolveError(f"验证码模型 batch 维度异常: {tuple(output.shape)}")

        indices = torch.argmax(logits, dim=-1).cpu().tolist()
        expression = decode_ctc(indices[:MAX_DECODE_STEPS], self.charset)
        if not expression:
            raise CaptchaSolveError("验证码模型未识别出内容。")
        return expression
