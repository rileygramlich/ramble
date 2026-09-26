"""Microphone capture: start, stop, get 16 kHz mono float32 back."""
from __future__ import annotations

import threading

import numpy as np

from .transcribe import SAMPLE_RATE


class Recorder:
    def __init__(self):
        self._chunks: list[np.ndarray] = []
        self._lock = threading.Lock()
        self._stream = None

    @property
    def recording(self) -> bool:
        return self._stream is not None

    def start(self) -> None:
        import sounddevice as sd

        with self._lock:
            self._chunks = []
        self._stream = sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32", callback=self._take)
        self._stream.start()

    def _take(self, data, frames, time, status):
        with self._lock:
            self._chunks.append(data.copy())

    def stop(self) -> np.ndarray:
        stream, self._stream = self._stream, None
        if stream is not None:
            stream.stop()
            stream.close()
        with self._lock:
            chunks, self._chunks = self._chunks, []
        return np.concatenate(chunks).flatten() if chunks else np.zeros(0, dtype=np.float32)


def is_speech(audio: np.ndarray) -> bool:
    """Too short or too quiet to be words. Whisper invents "Thank you." from silence."""
    if len(audio) < SAMPLE_RATE * 0.3:
        return False
    return float(np.sqrt(np.mean(audio**2))) > 0.003
