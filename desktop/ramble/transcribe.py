"""Speech to text, entirely on this machine.

Two engines behind one call. `mlx` uses Apple's MLX on Apple Silicon and is the
fastest option on a Mac. `faster-whisper` (CTranslate2) runs anywhere, on the CPU
or an NVIDIA GPU. Models download once, on first use, into the usual
Hugging Face cache.
"""
from __future__ import annotations

import glob
import os

import numpy as np

SAMPLE_RATE = 16_000

DEFAULT_MODELS = {
    "mlx": "mlx-community/whisper-large-v3-turbo",
    "faster-whisper": "small.en",
}


def _load_nvidia_libraries() -> bool:
    """Make NVIDIA's pip-installed CUDA libraries (the `gpu` extra) visible to CTranslate2.

    Without them, faster-whisper loads the model onto the GPU, fails at the first
    sentence ("libcublas.so.12 is not found"), and quietly falls back to the CPU,
    which is about 13x slower for large-v3-turbo. True if the libraries are there.
    """
    try:
        import ctypes
        import nvidia.cublas
        import nvidia.cudnn
    except ImportError:
        return False
    pattern = "*.dll" if os.name == "nt" else "lib*.so*"
    for package in (nvidia.cublas, nvidia.cudnn):
        for folder in package.__path__:
            for lib in sorted(glob.glob(os.path.join(folder, "bin" if os.name == "nt" else "lib", pattern))):
                try:
                    ctypes.CDLL(lib, mode=getattr(ctypes, "RTLD_GLOBAL", 0))
                except OSError:
                    pass
    return True


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
        self.device = None  # "cuda" or "cpu", once faster-whisper has run

    def load(self) -> None:
        """Load the model now, so the first dictation isn't the slow one."""
        self(np.zeros(SAMPLE_RATE // 2, dtype=np.float32))

    def __call__(self, audio: np.ndarray, vocabulary: list[str] = ()) -> str:
        """Extra vocabulary (e.g. sent by the phone) is added to this machine's own."""
        audio = np.asarray(audio, dtype=np.float32).flatten()
        prompt = self.prompt
        if vocabulary:
            prompt = ((self.prompt or "").rstrip(".") + ", " if self.prompt else "") + ", ".join(vocabulary) + "."
        if self.engine == "mlx":
            import mlx_whisper

            result = mlx_whisper.transcribe(
                audio,
                path_or_hf_repo=self.model,
                language=self.language,
                initial_prompt=prompt,
                condition_on_previous_text=False,
            )
            return result["text"].strip()

        try:
            return self._faster_whisper(audio, self.device or "auto", prompt)
        except RuntimeError as e:
            # An NVIDIA card without the CUDA libraries installed: use the CPU instead.
            if not any(word in str(e).lower() for word in ("cuda", "cublas", "cudnn")):
                raise
            print("! The GPU can't be used (" + str(e).split("\n")[0] + "), so speech runs on the CPU, "
                  "which is much slower. With an NVIDIA card: uv sync --extra gpu", flush=True)
            self._fw = None
            self.device = "cpu"
            return self._faster_whisper(audio, "cpu", prompt)

    def _faster_whisper(self, audio: np.ndarray, device: str, prompt: str | None) -> str:
        if self._fw is None:
            _load_nvidia_libraries()
            import ctranslate2
            from faster_whisper import WhisperModel

            if device == "auto":
                device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
            # int8 weights with float16 maths on the GPU: as accurate as float16 here, a
            # little faster, and half the memory (it shares the card with Ollama).
            compute = "int8_float16" if device == "cuda" else "int8"
            self._fw = WhisperModel(self.model, device=device, compute_type=compute)
            self.device = device
            print(f"Speech model {self.model} on the {'GPU' if device == 'cuda' else 'CPU'} ({compute})", flush=True)
        segments, _ = self._fw.transcribe(
            audio,
            language=self.language,
            initial_prompt=prompt,
            vad_filter=True,
            condition_on_previous_text=False,
            beam_size=1,
        )
        # Segments are lazy; decoding (and any CUDA failure) happens while joining.
        return " ".join(s.text.strip() for s in segments).strip()
