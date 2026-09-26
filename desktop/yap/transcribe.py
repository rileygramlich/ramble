"""Speech to text, entirely on this machine.

Two engines behind one call. `mlx` uses Apple's MLX on Apple Silicon and is the
fastest option on a Mac. `faster-whisper` (CTranslate2) runs anywhere, on the CPU
or an NVIDIA GPU. Models download once, on first use, into the usual
Hugging Face cache.
"""
from __future__ import annotations

import numpy as np

SAMPLE_RATE = 16_000

DEFAULT_MODELS = {
    "mlx": "mlx-community/whisper-large-v3-turbo",
    "faster-whisper": "small.en",
}


class Transcriber:
    def __init__(self, engine: str, model: str = "", language: str = "en", vocabulary: list[str] = ()):
        if engine not in DEFAULT_MODELS:
            raise SystemExit(f"Unknown engine '{engine}'. Use one of: {', '.join(DEFAULT_MODELS)}")
        self.engine = engine
        self.model = model or DEFAULT_MODELS[engine]
        self.language = language
        # Whisper spells names it has just "seen" in the prompt much more reliably.
        self.prompt = ", ".join(vocabulary) + "." if vocabulary else None
        self._fw = None

    def load(self) -> None:
        """Load the model now, so the first dictation isn't the slow one."""
        self(np.zeros(SAMPLE_RATE // 2, dtype=np.float32))

    def __call__(self, audio: np.ndarray) -> str:
        audio = np.asarray(audio, dtype=np.float32).flatten()
        if self.engine == "mlx":
            import mlx_whisper

            result = mlx_whisper.transcribe(
                audio,
                path_or_hf_repo=self.model,
                language=self.language,
                initial_prompt=self.prompt,
                condition_on_previous_text=False,
            )
            return result["text"].strip()

        try:
            return self._faster_whisper(audio, "auto")
        except RuntimeError as e:
            # An NVIDIA card without the CUDA libraries installed: use the CPU instead.
            if not any(word in str(e).lower() for word in ("cuda", "cublas", "cudnn")):
                raise
            self._fw = None
            return self._faster_whisper(audio, "cpu")

    def _faster_whisper(self, audio: np.ndarray, device: str) -> str:
        if self._fw is None:
            from faster_whisper import WhisperModel

            self._fw = WhisperModel(self.model, device=device, compute_type="int8")
        segments, _ = self._fw.transcribe(
            audio,
            language=self.language,
            initial_prompt=self.prompt,
            vad_filter=True,
            condition_on_previous_text=False,
            beam_size=1,
        )
        # Segments are lazy; decoding (and any CUDA failure) happens while joining.
        return " ".join(s.text.strip() for s in segments).strip()
