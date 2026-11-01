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

  it("breaks the chosen zone with the chosen error and shows how the action went", async () => {
    render(<DemoPanel onClose={() => {}} />);
    const zone = (await screen.findByLabelText("Zone")) as HTMLSelectElement;
    const error = screen.getByLabelText("Error") as HTMLSelectElement;
    await waitFor(() => expect(zone.options.length).toBe(40));
    await waitFor(() => expect(error.options.length).toBeGreaterThan(1));
    fireEvent.change(zone, { target: { value: "bluefin-sushi.test." } });
    fireEvent.change(error, { target: { value: "E1" } });
    fireEvent.click(screen.getByRole("button", { name: "Break this zone" }));

    expect(await screen.findByText("break bluefin-sushi.test E1")).toBeTruthy();
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Passed"));
  });

  it("asks before a reset and closes with Escape", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const onClose = vi.fn();
    render(<DemoPanel onClose={onClose} />);
    fireEvent.click(screen.getByRole("button", { name: "Reset hosting" }));
    expect(confirm).toHaveBeenCalledOnce();
    expect(screen.getByRole("status").textContent).toBe("No action yet.");

    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalled();
    confirm.mockRestore();
  });
});
