import type { TenantProfile } from "../../../packages/contracts/src/index.js";
import type { DocumentStore } from "../../../packages/core/src/memory.js";

/** Synthetic parties copied from the pinned Peppol example. Not real customers. */
export const LOCAL_SUPPLIER: TenantProfile = {
  id: "tenant-supplier",
  legalName: "SupplierOfficialName Ltd",
  companyId: "GB983294",
  vatId: "GB1232434",
  endpoint: { schemeId: "0088", value: "9482348239847239874" },
  countryCode: "GB",
};

export const LOCAL_BUYER: TenantProfile = {
  id: "tenant-buyer",
  legalName: "Buyer Official Name",
  companyId: "39937423947",
  vatId: "SE4598375937",
  endpoint: { schemeId: "0002", value: "FR23342" },
  countryCode: "SE",
};

export function applyLocalSeed(env: "local" | "production", store: DocumentStore): void {
  if (env !== "local") throw new Error("Lokálny seed je v produkcii zakázaný.");
  store.putTenant(LOCAL_SUPPLIER);
  store.putMember("uid-supplier", LOCAL_SUPPLIER.id, ["owner"]);
  store.putTenant(LOCAL_BUYER);
  store.putMember("uid-buyer", LOCAL_BUYER.id, ["owner"]);
  store.putMember("uid-reader", LOCAL_BUYER.id, ["reader"]);
}
