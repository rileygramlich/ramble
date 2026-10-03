"""Platform specifics that can be checked on any machine."""
from pathlib import Path

from ramble import config, output


def test_windows_folders(monkeypatch, tmp_path):
    monkeypatch.setattr(config, "IS_WINDOWS", True)
    monkeypatch.setenv("APPDATA", str(tmp_path / "Roaming"))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "Local"))
    cfg_dir, data_dir = config._dirs()
    assert cfg_dir == tmp_path / "Roaming" / "Ramble"
    assert data_dir == tmp_path / "Local" / "Ramble"


def test_old_yap_folders_are_kept(monkeypatch, tmp_path):
    monkeypatch.setattr(config, "IS_WINDOWS", False)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "data"))
    (tmp_path / "config" / "yap").mkdir(parents=True)
    assert config._dirs() == (tmp_path / "config" / "yap", tmp_path / "data" / "yap")


def test_new_installs_use_ramble_folders(monkeypatch, tmp_path):
    monkeypatch.setattr(config, "IS_WINDOWS", False)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "data"))
    assert config._dirs() == (tmp_path / "config" / "ramble", tmp_path / "data" / "ramble")


def test_windows_notification_and_sound_never_raise(monkeypatch):
    calls = []
    monkeypatch.setattr(output, "IS_MAC", False)
    monkeypatch.setattr(output, "IS_WINDOWS", True)
    monkeypatch.setattr(output.subprocess, "Popen", lambda args, **kw: calls.append(args))
    output.notify("It's on the clipboard")
    assert calls and calls[0][0] == "powershell"
    assert "It''s on the clipboard" in calls[0][-1]  # the quote is escaped for PowerShell
    output.sound("start")  # off Windows there's no winsound: it must quietly do nothing


def test_example_config_parses(tmp_path):
    path = Path(tmp_path) / "config.toml"
    path.write_text(config.EXAMPLE, encoding="utf-8")
    loaded = config.load(path)
    assert loaded.vocabulary
