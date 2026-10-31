import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { HighlightStyle, StreamLanguage, syntaxHighlighting } from "@codemirror/language";
import { shell } from "@codemirror/legacy-modes/mode/shell";
import { unifiedMergeView } from "@codemirror/merge";
import { EditorState, type Extension } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { tags } from "@lezer/highlight";
import { useEffect, useRef } from "react";

// The fix script in CodeMirror 6: dark, like the design's editor, with shell highlighting.

const highlight = HighlightStyle.define([
  { tag: tags.comment, color: "#9AA3AF", fontStyle: "italic" },
  { tag: [tags.keyword, tags.controlKeyword], color: "#FF9B8F" },
  { tag: [tags.string, tags.special(tags.string)], color: "#A5D6FF" },
  { tag: [tags.variableName, tags.definition(tags.variableName)], color: "#FFC27A" },
  { tag: [tags.number, tags.operator], color: "#D2A8FF" },
]);

const theme = EditorView.theme(
  {
    "&": { backgroundColor: "#14171C", color: "#E6E9ED", borderRadius: "8px", fontSize: "13px" },
    ".cm-content": { fontFamily: "var(--mono)", lineHeight: "1.6", padding: "12px 0", caretColor: "#E6E9ED" },
    ".cm-gutters": { backgroundColor: "#14171C", color: "#6B7380", border: "none", borderRadius: "8px 0 0 8px" },
    ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: "#1C2028" },
    "&.cm-focused": { outline: "2px solid #1F5EDC", outlineOffset: "2px" },
    "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection": { backgroundColor: "#2B3A55" },
    ".cm-cursor": { borderLeftColor: "#E6E9ED" },
    // the diff (unified merge view): what the admin changed against the generated script
    ".cm-changedLine": { backgroundColor: "#173A26" },
    ".cm-changedText": { background: "#1F5A36" },
    ".cm-deletedChunk": { backgroundColor: "#4A1D1A" },
    ".cm-deletedChunk .cm-deletedText": { background: "#6B2620" },
  },
  { dark: true },
);

const base: Extension[] = [lineNumbers(), StreamLanguage.define(shell), syntaxHighlighting(highlight), theme];

/** The editable script. `value` set from outside (e.g. "Reset to generated") replaces the text. */
export function ScriptEditor({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const changed = useRef(onChange);
  changed.current = onChange;

  useEffect(() => {
    view.current = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: value,
        extensions: [
          ...base,
          history(),
          keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
          EditorView.contentAttributes.of({ "aria-label": label }),
          EditorView.updateListener.of((u) => u.docChanged && changed.current(u.state.doc.toString())),
        ],
      }),
    });
    return () => view.current?.destroy();
  }, []); // created once; later values come through the effect below

  useEffect(() => {
    const v = view.current;
    if (v && v.state.doc.toString() !== value) {
      v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: value } });
    }
  }, [value]);

  return <div ref={host} className="editor" />;
}

/** Read-only: the current script with the changes against the generated one marked. */
export function ScriptDiff({ original, current, label }: { original: string; current: string; label: string }) {
  const host = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const v = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: current,
        extensions: [
          ...base,
          unifiedMergeView({ original, mergeControls: false, gutter: true }),
          EditorView.editable.of(false),
          EditorState.readOnly.of(true),
          EditorView.contentAttributes.of({ "aria-label": label }),
        ],
      }),
    });
    return () => v.destroy();
  }, [original, current, label]);

  return <div ref={host} className="editor" />;
}
