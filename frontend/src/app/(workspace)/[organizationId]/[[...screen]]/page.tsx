import { notFound } from "next/navigation";
import { Overview } from "@/features/overview";
import { Incidents, IncidentDetail } from "@/features/incidents";
import { Documents, DocumentDetail } from "@/features/documents";
import { KnowledgeSearch } from "@/features/search";
import { Assistant } from "@/features/assistant";
import { WorkspaceSettings } from "@/features/settings";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export default async function Screen({
  params,
}: {
  params: Promise<{ organizationId: string; screen?: string[] }>;
}) {
  const { organizationId, screen = [] } = await params;
  if (!uuid.test(organizationId) || screen.length > 2) notFound();
  const [area = "overview", id] = screen;
  if (id && !uuid.test(id)) notFound();
  if (area === "incidents")
    return id ? <IncidentDetail id={id} /> : <Incidents />;
  if (area === "documents")
    return id ? <DocumentDetail id={id} /> : <Documents />;
  if (area === "assistant") return <Assistant id={id} />;
  if (id) notFound();
  if (area === "overview") return <Overview />;
  if (area === "search") return <KnowledgeSearch />;
  if (area === "settings") return <WorkspaceSettings />;
  notFound();
}
