"use client";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { Button } from "@astryxdesign/core/Button";
import { Layout, LayoutContent } from "@astryxdesign/core/Layout";
import {
  ArrowRight,
  TriangleAlert,
  BookOpen,
  MessagesSquare,
} from "lucide-react";
import { useOrganization } from "@/components/workspace";
import { api, tenant, keys } from "@/lib/api";
import type { Document, DocumentPage, IncidentPage } from "@/lib/contracts";
import {
  PageHeader,
  Status,
  Loading,
  Feedback,
  date,
  Empty,
} from "@/components/ui";
export function Overview() {
  const { organization: org } = useOrganization();
  const incidents = useQuery({
    queryKey: [...keys.incidents(org.id), "recent"],
    queryFn: ({ signal }) =>
      api<IncidentPage>(`${tenant(org.id)}/incidents?page=0&size=5`, {
        signal,
      }),
  });
  const docs = useQuery({
    queryKey: [...keys.documents(org.id), "recent"],
    queryFn: ({ signal }) =>
      api<DocumentPage<Document>>(`${tenant(org.id)}/documents?page=0&size=5`, {
        signal,
      }),
  });
  return (
    <Layout height="auto" padding={6}>
      <LayoutContent padding={6}>
        <div className="page">
          <PageHeader
            title="Overview"
            description={`Your engineering workspace · ${org.name}`}
            actions={
              <Button
                label="Search knowledge"
                href={`/${org.id}/search`}
                variant="primary"
              />
            }
          />
          <div className="overview-intro">
            <h2>Context for your next investigation.</h2>
            <p className="muted">
              Start with what happened. Connect it to what your team knows.
            </p>
            <div className="quick-links">
              <Link href={`/${org.id}/incidents`}>
                <TriangleAlert />
                <strong>Track an incident</strong>
                <ArrowRight />
              </Link>
              <Link href={`/${org.id}/documents`}>
                <BookOpen />
                <strong>Build your knowledge base</strong>
                <ArrowRight />
              </Link>
              <Link href={`/${org.id}/assistant`}>
                <MessagesSquare />
                <strong>Investigate with evidence</strong>
                <ArrowRight />
              </Link>
            </div>
          </div>
          <div className="overview-grid">
            <section className="panel">
              <div className="section-heading">
                <h2>Recent incidents</h2>
                <Link href={`/${org.id}/incidents`}>View all →</Link>
              </div>
              {incidents.isPending ? (
                <Loading />
              ) : incidents.error ? (
                <Feedback
                  error={incidents.error}
                  retry={() => {
                    void incidents.refetch();
                  }}
                />
              ) : incidents.data.items.length ? (
                <>
                  <p className="muted small">
                    {incidents.data.totalElements} incidents in this
                    organization
                  </p>
                  {incidents.data.items.map((i) => (
                    <Link
                      className="resource-row"
                      key={i.id}
                      href={`/${org.id}/incidents/${i.id}`}
                    >
                      <div>
                        <strong>{i.title}</strong>
                        <p className="small muted">{date(i.createdAt)}</p>
                      </div>
                      <Status value={i.status} />
                    </Link>
                  ))}
                </>
              ) : (
                <Empty
                  title="No incidents yet"
                  description="Record the symptoms, investigation, and resolution of an incident."
                />
              )}
            </section>
            <section className="panel">
              <div className="section-heading">
                <h2>Recent documents</h2>
                <Link href={`/${org.id}/documents`}>View all →</Link>
              </div>
              {docs.isPending ? (
                <Loading />
              ) : docs.error ? (
                <Feedback
                  error={docs.error}
                  retry={() => {
                    void docs.refetch();
                  }}
                />
              ) : docs.data.content.length ? (
                <>
                  <p className="muted small">
                    {docs.data.totalElements} stored documents in this
                    organization · storage status below
                  </p>
                  {docs.data.content.map((d) => (
                    <Link
                      className="resource-row"
                      key={d.id}
                      href={`/${org.id}/documents/${d.id}`}
                    >
                      <div>
                        <strong>{d.title}</strong>
                        <p className="small muted">
                          Revision{" "}
                          {d.currentRevision?.revisionNumber ?? "pending"} ·{" "}
                          {date(d.modifiedAt)}
                        </p>
                      </div>
                      <Status value={d.status} />
                    </Link>
                  ))}
                </>
              ) : (
                <Empty
                  title="Knowledge starts here"
                  description="Upload runbooks, postmortems, and engineering notes as PDF, Markdown, or text."
                />
              )}
            </section>
          </div>
        </div>
      </LayoutContent>
    </Layout>
  );
}
