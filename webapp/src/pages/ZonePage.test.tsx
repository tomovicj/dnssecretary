import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ZonePage } from "./ZonePage";

// The page talks to the mock backend; a script that contains "BAD" fails in its sandbox.
vi.mock("../api/client", async (original) => {
  const real = await original<typeof import("../api/client")>();
  const { mockBackend } = await import("../api/mock");
  return { ...real, api: mockBackend({ jobMs: 60 }) };
});

// CodeMirror needs real layout; here a textarea stands in for it (ScriptEditor.test.tsx tests the real one).
vi.mock("../components/ScriptEditor", () => ({
  ScriptEditor: ({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) => (
    <textarea aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} />
  ),
  ScriptDiff: ({ current }: { current: string }) => <pre data-testid="diff">{current}</pre>,
}));

function open(zone: string) {
  render(
    <MemoryRouter initialEntries={[`/zones/${zone}`]}>
      <Routes>
        <Route path="/zones/:zone" element={<ZonePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

const editor = () => screen.getByLabelText("Fix script") as HTMLTextAreaElement;
const run = () => screen.getByRole("heading", { name: "Result of the last run" }).closest(".run") as HTMLElement;

describe("ZonePage", () => {
  afterEach(cleanup);

  it("explains the error with the catalogue's texts and shows the generated script", async () => {
    open("a1-expired.test");
    expect(await screen.findByRole("heading", { name: "Expired signature" })).toBeTruthy();
    const card = screen.getByRole("heading", { name: "Expired signature" }).closest("article")!;
    expect(within(card).getByText(/3 findings\. Example:/)).toBeTruthy();
    expect(within(card).getByText("2026-10-24T11:28:19Z")).toBeTruthy();
    expect(within(card).getByText("SERVFAIL · EDE 7 (Signature Expired)")).toBeTruthy();
    expect(within(card).getByText("Re-sign the zone with new dates.")).toBeTruthy();
    expect(within(card).getByRole("link", { name: "RFC 4035 §5.3.1" }).getAttribute("href")).toBe(
      "https://www.rfc-editor.org/rfc/rfc4035#section-5.3.1",
    );
    expect(screen.getByText(/fixed by the zone \(automatic\)/)).toBeTruthy();
    expect(editor().value).toContain("dnssec-signzone");
    expect(screen.getByText("Generated · unchanged")).toBeTruthy();
  });

  it("simulates an edited script and shows its log and verify result", async () => {
    open("a1-expired.test");
    await screen.findByRole("heading", { name: "Expired signature" });
    fireEvent.change(editor(), { target: { value: "echo BAD" } });
    expect(screen.getByText("Edited · not simulated yet")).toBeTruthy();

    fireEvent.click(screen.getByRole("tab", { name: "Changes vs generated" }));
    expect(screen.getByTestId("diff").textContent).toBe("echo BAD");
    fireEvent.click(screen.getByRole("tab", { name: "Script" }));

    fireEvent.click(screen.getByRole("button", { name: "Simulate" }));
    await waitFor(() => expect(within(run()).getByText(/sim-\d+ failed/)).toBeTruthy());
    expect(within(run()).getByText(/fix script exit: 1/)).toBeTruthy();

    fireEvent.click(within(run()).getByRole("tab", { name: "Verify result" }));
    expect(within(run()).getByText("Still found: A1.")).toBeTruthy();
    expect(within(run()).getAllByText("FAIL").length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole("button", { name: "Reset to generated" }));
    expect(editor().value).toContain("dnssec-signzone");
  });

  it("executes after a passing simulation, and the zone becomes healthy", async () => {
    open("b1-ds-stale.test");
    await screen.findByRole("heading", { name: "DS points to a key that does not exist" });
    fireEvent.click(screen.getByRole("button", { name: "Execute on hosting" }));

    await waitFor(() => expect(within(run()).getByText(/exec-\d+ passed/)).toBeTruthy());
    expect(within(run()).getByText(/simulation passed; executing on the lab/)).toBeTruthy();
    expect(await screen.findByRole("heading", { name: "No problem found" })).toBeTruthy();
    const history = screen.getByRole("heading", { name: "History" }).closest("article")!;
    expect(within(history).getByText("Execution · generated script")).toBeTruthy();
  });

  it("does not execute a script whose simulation fails", async () => {
    open("f1-stale-secondary.test");
    await screen.findByRole("heading", { name: /Secondary server/ });
    fireEvent.change(editor(), { target: { value: "echo BAD" } });
    fireEvent.click(screen.getByRole("button", { name: "Execute on hosting" }));

    expect(await screen.findByText("the simulation did not pass, nothing was executed")).toBeTruthy();
    expect(screen.getByRole("heading", { name: /Secondary server/ })).toBeTruthy(); // still broken
  });

  it("asks before executing without a simulation", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    open("a6-expiring.test");
    await screen.findByRole("heading", { name: /Signature expires soon/ });
    fireEvent.click(screen.getByLabelText("Skip the simulation (asks to confirm)"));
    fireEvent.click(screen.getByRole("button", { name: "Execute on hosting" }));

    expect(confirm).toHaveBeenCalledOnce();
    expect(within(run()).getByText("No simulation or execution yet.")).toBeTruthy();
    confirm.mockRestore();
  });
});
