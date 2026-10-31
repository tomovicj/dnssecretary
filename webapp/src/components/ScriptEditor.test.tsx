import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ScriptDiff, ScriptEditor } from "./ScriptEditor";

// The real CodeMirror in jsdom: no layout, but the document and the edits are there.
describe("ScriptEditor", () => {
  afterEach(cleanup);

  it("shows the script and takes a new value from outside", () => {
    const { container, rerender } = render(<ScriptEditor value={"echo one\n"} onChange={vi.fn()} label="Fix script" />);
    const content = () => container.querySelector(".cm-content")!;
    expect(content().textContent).toContain("echo one");
    expect(content().getAttribute("aria-label")).toBe("Fix script");

    rerender(<ScriptEditor value={"echo two\n"} onChange={vi.fn()} label="Fix script" />);
    expect(content().textContent).toContain("echo two");
  });

  it("marks what changed against the generated script", () => {
    const { container } = render(<ScriptDiff original={"a\nb\n"} current={"a\nc\n"} label="Changes" />);
    expect(container.querySelector(".cm-changedLine")).not.toBeNull();
    expect(container.querySelector(".cm-deletedChunk")?.textContent).toContain("b");
  });
});
