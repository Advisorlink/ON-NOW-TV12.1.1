"""Copy the original native SOLO player visuals, not its WebView/party transport.
The source app is read-only. Documented adapters remove unsupported engines/party
widgets; loading, dock, track/stream/info/aspect sheets keep their original design.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[3]
source = root / 'android/vesper-tv/app/src/main/java/tv/vesper/app'
out = root / 'android/vesper-native/app/src/main/java/tv/onnowtv/vespernative/playback/v2'
out.mkdir(parents=True, exist_ok=True)

def remove_block(text, start):
    begin = text.index(start)
    brace = text.index('{', begin)
    depth = 1
    end = brace + 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[:begin] + text[end:]

def prepare(text):
    text = text.replace('package tv.vesper.app', 'package tv.onnowtv.vespernative.playback.v2')
    text = text.replace('package tv.onnowtv.vespernative.playback.v2', 'package tv.onnowtv.vespernative.playback.v2\n\nimport tv.onnowtv.vespernative.BuildConfig', 1)
    # Native accessibility semantics for every displayed label/icon/artwork and
    # focus target, without modifying the original resource or visual modifiers.
    text = text.replace('import androidx.compose.material3.Text', 'import tv.onnowtv.vespernative.playback.v2.TaggedText as Text')
    text = text.replace('import androidx.compose.material3.Icon', 'import tv.onnowtv.vespernative.playback.v2.TaggedIcon as Icon')
    text = text.replace('import coil.compose.AsyncImage', 'import tv.onnowtv.vespernative.playback.v2.TaggedImage as AsyncImage')
    text = text.replace('.focusable()', '.nativeFocusTag().focusable()')
    text = text.replace('.clickable(', '.nativeClickTag().clickable(').replace('.clickable {', '.nativeClickTag().clickable {')
    text = text.replace('.pointerInput(', '.nativeGestureTag().pointerInput(')
    return '// Generated from original V2 native UI by scripts/sync-player-reference.py.\n' + text

text = (source / 'PlayerOverlay.kt').read_text()
# Party UI lives after the generic StateFlow collector and is intentionally not
# copied. There is no fabricated PartyVoiceManager or inoperative party service.
cut = text.index('@Composable\nprivate fun PartyVoiceLayer(')
text = text[:cut]
text = re.sub(r'^    partyVoice: PartyVoiceManager\? = null,\n', '', text, flags=re.M)
text = re.sub(r'^    partyDrawerOpen:.*\n|^    partyRole:.*\n', '', text, flags=re.M)
text = text.replace('val inParty = partyVoice != null', 'val inParty = false // Native solo build; no party transport is fabricated.')
text = remove_block(text, '        if (partyVoice != null) {')
# Treat an already-paused resumed stream as loaded once READY is reported too.
# Otherwise a READY-but-paused video could retain the initial splash forever.
text = text.replace('if (playing) hasEverPlayed = true', 'if (playing || !loading) hasEverPlayed = true')
text = text.replace('LaunchedEffect(playing) {', 'LaunchedEffect(playing, loading) {')
text = text.replace('val bump: () -> Unit = { lastActivity = System.currentTimeMillis() }', 'val bump: () -> Unit = { lastActivity = System.currentTimeMillis(); dockVisible = true }')
text = re.sub(r'    LaunchedEffect\(userActivityTs\) \{.*?\n    \}', '''    LaunchedEffect(userActivityTs) {
        lastActivity = userActivityTs
        dockVisible = true
    }
    LaunchedEffect(lastActivity, hasEverPlayed, dockVisible) {
        if (dockVisible) {
            val timeout = if (!hasEverPlayed) 10_000L else 5_000L
            delay(timeout)
            if (System.currentTimeMillis() - lastActivity >= timeout) dockVisible = false
        }
    }''', text, count=1, flags=re.S)
(out / 'PlayerOverlay.kt').write_text(prepare(text))
for name in ['EnginePickerSheet.kt', 'AspectPickerSheet.kt']:
    text = (source / name).read_text()
    if name == 'EnginePickerSheet.kt':
        text = '\n'.join(line for line in text.splitlines() if not re.search(r'EngineRow\("(?:mpv|vlc|exo_ffmpeg)"', line)) + '\n'
    (out / name).write_text(prepare(text))