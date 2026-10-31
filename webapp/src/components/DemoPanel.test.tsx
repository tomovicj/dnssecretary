import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DemoPanel } from "./DemoPanel";

vi.mock("../api/client", async (original) => {
  const real = await original<typeof import("../api/client")>();
  const { mockBackend } = await import("../api/mock");
  return { ...real, api: mockBackend({ jobMs: 60 }) };
});

describe("DemoPanel", () => {
  afterEach(cleanup);

  it("breaks the chosen zone and shows how the action went", async () => {
    render(<DemoPanel onClose={() => {}} />);
    const select = (await screen.findByLabelText("Zone")) as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBe(28)); // e1/e3 have their own buttons
    fireEvent.change(select, { target: { value: "d1-nsec-broken.test." } });
    fireEvent.click(screen.getByRole("button", { name: "Break this zone" }));

    expect(await screen.findByText("break d1-nsec-broken.test")).toBeTruthy();
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Passed"));
  });

  it("asks before a reset and closes with Escape", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const onClose = vi.fn();
    render(<DemoPanel onClose={onClose} />);
    fireEvent.click(screen.getByRole("button", { name: "Reset lab (30 broken)" }));
    expect(confirm).toHaveBeenCalledOnce();
    expect(screen.getByRole("status").textContent).toBe("No action yet.");

    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalled();
    confirm.mockRestore();
  });
});
