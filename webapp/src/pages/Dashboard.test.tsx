import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";
import type { Backend } from "../api/client";
import { mockBackend } from "../api/mock";
import { useZones } from "../api/useZones";
import { Dashboard } from "./Dashboard";

function Harness({ backend }: { backend: Backend }) {
  const live = useZones(backend);
  return (
    <MemoryRouter>
      <Dashboard live={live} now={Date.now()} />
    </MemoryRouter>
  );
}

/** The table's body rows (not the header). */
function rows() {
  return within(screen.getByRole("table")).getAllByRole("row").slice(1);
}

describe("Dashboard", () => {
  afterEach(cleanup);

  it("shows the counters and the zones with a problem, healthy ones folded", async () => {
    render(<Harness backend={mockBackend()} />);
    expect(await screen.findByRole("cell", { name: "northwind-coffee.test" })).toBeTruthy();

    const summary = screen.getByRole("region", { name: "Summary" });
    expect(within(summary).getByText("4 of 7")).toBeTruthy();
    expect(within(summary).getByText("1 running · 1 failed · 1 without a script")).toBeTruthy();
    expect(rows()).toHaveLength(7);
    expect(screen.getByText("33 healthy zones")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Show healthy zones" }));
    expect(rows()).toHaveLength(40);
  });

  it("filters by state and searches by name", async () => {
    render(<Harness backend={mockBackend()} />);
    await screen.findByRole("cell", { name: "northwind-coffee.test" });

    fireEvent.click(screen.getByRole("button", { name: "At risk 3" }));
    expect(screen.getByRole("button", { name: "At risk 3" }).getAttribute("aria-pressed")).toBe("true");
    expect(rows()).toHaveLength(3);

    fireEvent.click(screen.getByRole("button", { name: "All 40" }));
    fireEvent.change(screen.getByLabelText("Search zones"), { target: { value: "lindqvist" } });
    expect(rows()).toHaveLength(1);
    expect(screen.getByRole("link", { name: "Open lindqvist-law.test" }).getAttribute("href")).toBe("/zones/lindqvist-law.test");
  });

  it("updates a row live from the zone events", async () => {
    const backend = mockBackend({ jobMs: 50 });
    render(<Harness backend={backend} />);
    await screen.findByRole("cell", { name: "northwind-coffee.test" });

    await act(async () => {
      await backend.simulate("northwind-coffee.test", "echo BAD");
    });
    const a1 = screen.getByRole("cell", { name: "northwind-coffee.test" }).closest("tr")!;
    expect(within(a1).getByText("Simulating")).toBeTruthy();
    expect(a1.className).toBe("fresh");
    expect(await within(a1).findByText("Failed")).toBeTruthy();
  });
});
