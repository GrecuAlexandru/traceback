"use client";
import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { TextArea } from "@astryxdesign/core/TextArea";
import { Selector } from "@astryxdesign/core/Selector";
import {
  Table,
  TableHeader,
  TableHeaderCell,
  TableBody,
  TableRow,
  TableCell,
} from "@astryxdesign/core/Table";
import { Layout, LayoutContent } from "@astryxdesign/core/Layout";
import { Plus, ArrowLeft } from "lucide-react";
import { useOrganization } from "@/components/workspace";
import { api, ApiError, tenant, keys, writes } from "@/lib/api";
import {
  incidentStatuses,
  type Incident,
  type IncidentPage,
  type IncidentStatus,
} from "@/lib/contracts";
import {
  PageHeader,
  Status,
  Loading,
  Feedback,
  date,
  Empty,
  Pager,
  Modal,
  Confirm,
} from "@/components/ui";
function IncidentForm({
  incident,
  onClose,
  onSaved,
  onReload,
}: {
  incident?: Incident;
  onClose: () => void;
  onSaved: (i: Incident) => void;
  onReload?: () => void;
}) {
  const { organization } = useOrganization();
  const [title, setTitle] = useState(incident?.title ?? "");
  const [description, setDescription] = useState(incident?.description ?? "");
  const [status, setStatus] = useState<IncidentStatus>(
    incident?.status ?? "OPEN",
  );
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const [submitted, setSubmitted] = useState(false);
  const titleBad = !title.trim() || title.length > 200;
  const descriptionBad = !description.trim() || description.length > 20000;
  async function save() {
    setSubmitted(true);
    if (titleBad || descriptionBad) return;
    setPending(true);
    setError(undefined);
    try {
      const data = incident
        ? await writes.updateIncident(organization.id, incident.id, {
            title,
            description,
            status,
            version: incident.version,
          })
        : await writes.createIncident(organization.id, { title, description });
      onSaved(data);
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }
  return (
    <Modal
      title={incident ? "Edit incident" : "New incident"}
      open
      onClose={pending ? () => {} : onClose}
    >
      <form
        className="form-stack"
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <TextInput
          label="Title"
          value={title}
          onChange={setTitle}
          isRequired
          isDisabled={pending}
          status={
            submitted && titleBad
              ? { type: "error", message: "Enter a title of 1–200 characters." }
              : undefined
          }
        />
        <TextArea
          label="Description"
          value={description}
          onChange={setDescription}
          isRequired
          isDisabled={pending}
          rows={7}
          description="Capture the symptoms, investigation, and resolution. Maximum 20,000 UTF-16 units."
          status={
            submitted && descriptionBad
              ? {
                  type: "error",
                  message: "Enter a description of 1–20,000 UTF-16 units.",
                }
              : undefined
          }
        />
        {incident ? (
          <Selector
            label="Status"
            value={status}
            options={incidentStatuses.map((s) => ({
              value: s,
              label: s.toLowerCase(),
            }))}
            onChange={(s) => setStatus(s as IncidentStatus)}
            isDisabled={pending}
          />
        ) : null}
        <Feedback
          error={error}
          retry={
            error instanceof ApiError && error.status === 409
              ? onReload
              : undefined
          }
        />
        {error instanceof ApiError && error.status === 409 ? (
          <p className="small muted">
            Reload closes this editor. Review the latest record before making
            your changes again.
          </p>
        ) : null}
        <div className="actions end">
          <Button label="Cancel" onClick={onClose} isDisabled={pending} />
          <Button
            label={incident ? "Save changes" : "Create incident"}
            variant="primary"
            type="submit"
            isLoading={pending}
            isDisabled={error instanceof ApiError && error.status === 409}
          />
        </div>
      </form>
    </Modal>
  );
}
export function Incidents() {
  const { organization: org } = useOrganization();
  const client = useQueryClient();
  const router = useRouter();
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState("all");
  const [create, setCreate] = useState(false);
  const query = useQuery({
    queryKey: [...keys.incidents(org.id), page, filter],
    queryFn: ({ signal }) =>
      api<IncidentPage>(
        `${tenant(org.id)}/incidents?page=${page}&size=20${filter === "all" ? "" : `&status=${filter}`}`,
        { signal },
      ),
  });
  return (
    <Layout height="auto" padding={6}>
      <LayoutContent padding={6}>
        <div className="page">
          <PageHeader
            title="Incidents"
            description="Investigate, resolve, and preserve what you learn."
            actions={
              org.role !== "VIEWER" ? (
                <Button
                  label="New incident"
                  icon={<Plus size={16} />}
                  variant="primary"
                  onClick={() => setCreate(true)}
                />
              ) : (
                <span className="muted small">Read-only membership</span>
              )
            }
          />
          <div className="toolbar">
            <Selector
              label="Status"
              value={filter}
              options={[
                { value: "all", label: "All statuses" },
                ...incidentStatuses.map((s) => ({
                  value: s,
                  label: s.toLowerCase(),
                })),
              ]}
              width={220}
              onChange={(v) => {
                setFilter(v);
                setPage(0);
              }}
            />
            <Button
              label="Refresh"
              variant="ghost"
              onClick={() => {
                void query.refetch();
              }}
            />
          </div>
          {query.isPending ? (
            <Loading label="Loading incidents" />
          ) : query.error ? (
            <Feedback
              error={query.error}
              retry={() => {
                void query.refetch();
              }}
            />
          ) : query.data.items.length ? (
            <section className="table-panel">
              <Table aria-label="Incidents" density="spacious" hasHover>
                <TableHeader>
                  <TableRow>
                    <TableHeaderCell>Incident</TableHeaderCell>
                    <TableHeaderCell>Status</TableHeaderCell>
                    <TableHeaderCell>Created</TableHeaderCell>
                    <TableHeaderCell>Version</TableHeaderCell>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {query.data.items.map((i) => (
                    <TableRow key={i.id}>
                      <TableCell>
                        <Link
                          className="table-title"
                          href={`/${org.id}/incidents/${i.id}`}
                        >
                          {i.title}
                        </Link>
                        <p className="mono small muted">{i.id.slice(0, 8)}</p>
                      </TableCell>
                      <TableCell>
                        <Status value={i.status} />
                      </TableCell>
                      <TableCell>
                        <span className="muted">{date(i.createdAt)}</span>
                      </TableCell>
                      <TableCell>
                        <span className="mono">{i.version}</span>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Pager
                page={page}
                total={query.data.totalElements}
                totalPages={query.data.totalPages}
                onChange={setPage}
              />
            </section>
          ) : (
            <Empty
              title={
                filter === "all"
                  ? "No incidents yet"
                  : "No incidents with this status"
              }
              description="An incident records what happened and what you learned."
            />
          )}
          {create ? (
            <IncidentForm
              onClose={() => setCreate(false)}
              onSaved={(i) => {
                void client.invalidateQueries({
                  queryKey: keys.incidents(org.id),
                });
                router.push(`/${org.id}/incidents/${i.id}`);
              }}
            />
          ) : null}
        </div>
      </LayoutContent>
    </Layout>
  );
}
export function IncidentDetail({ id }: { id: string }) {
  const { organization: org } = useOrganization();
  const client = useQueryClient();
  const router = useRouter();
  const [edit, setEdit] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const query = useQuery({
    queryKey: [...keys.incidents(org.id), id],
    queryFn: ({ signal }) =>
      api<Incident>(`${tenant(org.id)}/incidents/${id}`, { signal }),
  });
  async function remove() {
    if (!query.data) return;
    setPending(true);
    setError(undefined);
    try {
      await writes.deleteIncident(org.id, id, query.data.version);
      void client.invalidateQueries({ queryKey: keys.incidents(org.id) });
      router.push(`/${org.id}/incidents`);
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }
  function reload() {
    setEdit(false);
    setDeleting(false);
    setError(undefined);
    void query.refetch();
  }
  return (
    <Layout height="auto" padding={6}>
      <LayoutContent padding={6}>
        <div className="page">
          <Button
            label="All incidents"
            icon={<ArrowLeft size={16} />}
            width="fit-content"
            href={`/${org.id}/incidents`}
            variant="ghost"
          />
          {query.isPending ? (
            <Loading />
          ) : query.error ? (
            <Feedback
              error={query.error}
              retry={() => {
                void query.refetch();
              }}
            />
          ) : (
            <>
              <PageHeader
                title={query.data.title}
                description={`Created ${date(query.data.createdAt)}`}
                actions={
                  org.role !== "VIEWER" ? (
                    <>
                      <Button
                        label="Edit incident"
                        variant="primary"
                        onClick={() => setEdit(true)}
                      />
                      <Button
                        label="Delete incident"
                        variant="ghost"
                        onClick={() => {
                          setError(undefined);
                          setDeleting(true);
                        }}
                      />
                    </>
                  ) : (
                    <span className="muted small">Read-only membership</span>
                  )
                }
              />
              <section className="detail-meta">
                <Status value={query.data.status} />
                <span className="mono small muted">
                  Version {query.data.version}
                </span>
                <span className="mono small muted">{query.data.id}</span>
              </section>
              <section className="prose-panel">
                <h2>Investigation</h2>
                <p className="pre-wrap">{query.data.description}</p>
              </section>
              {edit ? (
                <IncidentForm
                  key={query.data.version}
                  incident={query.data}
                  onClose={() => setEdit(false)}
                  onReload={reload}
                  onSaved={(i) => {
                    client.setQueryData([...keys.incidents(org.id), id], i);
                    void client.invalidateQueries({
                      queryKey: keys.incidents(org.id),
                    });
                    setEdit(false);
                  }}
                />
              ) : null}
              {deleting ? (
                <Confirm
                  title="Delete incident?"
                  description="This removes the incident. Confirm only after checking you have selected the correct record."
                  pending={pending}
                  onReload={reload}
                  onConfirm={() => {
                    void remove();
                  }}
                  onClose={() => setDeleting(false)}
                  error={error}
                />
              ) : null}
              {error instanceof ApiError && error.status === 409 ? (
                <Feedback error={error} retry={reload} />
              ) : null}
            </>
          )}
        </div>
      </LayoutContent>
    </Layout>
  );
}
