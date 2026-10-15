"use client";
import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { TextArea } from "@astryxdesign/core/TextArea";
import { FileInput } from "@astryxdesign/core/FileInput";
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
import { Upload, ArrowLeft, Download, FileText } from "lucide-react";
import { useOrganization } from "@/components/workspace";
import {
  api,
  ApiError,
  tenant,
  keys,
  writes,
  validFile,
  download,
} from "@/lib/api";
import type {
  Document,
  DocumentPage,
  Revision,
  Indexing,
  Ingestion,
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
function UploadForm({
  document,
  onClose,
  onSaved,
  onReload,
}: {
  document?: Document;
  onClose: () => void;
  onSaved: (document: Document) => void;
  onReload?: () => void;
}) {
  const { organization, session } = useOrganization();
  const [title, setTitle] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [sourceMode, setSourceMode] = useState("file");
  const [notes, setNotes] = useState("");
  const selectedFile =
    sourceMode === "notes"
      ? notes.trim()
        ? new File([notes], "engineering-notes.md", { type: "text/markdown" })
        : null
      : file;
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const [submitted, setSubmitted] = useState(false);
  const fileValid =
    validFile(selectedFile, session.maxFileBytes) &&
    (sourceMode !== "notes" || notes.length <= 20000);
  async function save() {
    setSubmitted(true);
    if (!fileValid || (!document && (!title.trim() || title.length > 200)))
      return;
    setPending(true);
    setError(undefined);
    try {
      onSaved(
        document
          ? await writes.reviseDocument(
              organization.id,
              document.id,
              document.version,
              selectedFile!,
            )
          : await writes.uploadDocument(organization.id, title, selectedFile!),
      );
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }
  return (
    <Modal
      title={document ? "Upload new revision" : "Upload document"}
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
        {!document ? (
          <TextInput
            label="Document title"
            value={title}
            onChange={setTitle}
            isRequired
            isDisabled={pending}
            status={
              submitted && (!title.trim() || title.length > 200)
                ? {
                    type: "error",
                    message: "Enter a title of 1–200 characters.",
                  }
                : undefined
            }
          />
        ) : (
          <p>
            Replace the current source with an immutable new revision. Previous
            revisions remain in history; existing AI answers may become
            unavailable.
          </p>
        )}
        <Selector
          label="Source input"
          value={sourceMode}
          onChange={setSourceMode}
          isDisabled={pending}
          options={[
            { value: "file", label: "Upload a file" },
            { value: "notes", label: "Paste Markdown notes" },
          ]}
        />
        {sourceMode === "notes" ? (
          <TextArea
            label="Markdown notes"
            value={notes}
            onChange={setNotes}
            rows={8}
            isRequired
            isDisabled={pending}
            description="Up to 20,000 UTF-16 units. Saved as an immutable Markdown source and processed through the same validation and ingestion pipeline."
            status={
              submitted && !fileValid
                ? {
                    type: "error",
                    message: "Enter nonempty notes within the file size limit.",
                  }
                : undefined
            }
          />
        ) : (
          <FileInput
            label="Source file"
            accept=".pdf,.md,.markdown,.txt"
            value={file}
            onChange={(f) => setFile(Array.isArray(f) ? (f[0] ?? null) : f)}
            mode="dropzone"
            maxSize={session.maxFileBytes}
            isRequired
            isDisabled={pending}
            isLoading={pending}
            description={`PDF, Markdown, or TXT · up to ${(session.maxFileBytes / 1048576).toFixed(0)} MiB · PDF must contain extractable text`}
            status={
              submitted && !fileValid
                ? {
                    type: "error",
                    message:
                      "Choose a nonempty supported file within the size limit.",
                  }
                : undefined
            }
          />
        )}
        {pending ? <Loading label="Uploading and validating source" /> : null}
        <Feedback
          error={error}
          retry={
            error instanceof ApiError && error.status === 409
              ? onReload
              : undefined
          }
        />
        {error ? (
          <p className="muted small">
            If the response was lost, inspect the document list or revision
            history before uploading again. Uploads do not support idempotency
            keys.
          </p>
        ) : null}
        <div className="actions end">
          <Button label="Cancel" onClick={onClose} isDisabled={pending} />
          <Button
            label={document ? "Upload revision" : "Upload document"}
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
export function Documents() {
  const { organization: org } = useOrganization();
  const client = useQueryClient();
  const router = useRouter();
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState("AVAILABLE");
  const [upload, setUpload] = useState(false);
  const query = useQuery({
    queryKey: [...keys.documents(org.id), page, filter],
    queryFn: ({ signal }) =>
      api<DocumentPage<Document>>(
        `${tenant(org.id)}/documents?page=${page}&size=20${`&status=${filter}`}`,
        { signal },
      ),
  });
  return (
    <Layout height="auto" padding={6}>
      <LayoutContent padding={6}>
        <div className="page">
          <PageHeader
            title="Knowledge Base"
            description="Runbooks, postmortems, and the context behind your systems."
            actions={
              org.role !== "VIEWER" ? (
                <Button
                  label="Upload document"
                  variant="primary"
                  icon={<Upload size={16} />}
                  onClick={() => setUpload(true)}
                />
              ) : (
                <span className="muted small">Read-only membership</span>
              )
            }
          />
          <div className="toolbar">
            <Selector
              label="Storage status"
              value={filter}
              options={[
                { value: "AVAILABLE", label: "Stored documents" },
                { value: "UPLOADING", label: "Uploading" },
                { value: "FAILED", label: "Failed" },
                { value: "DELETED", label: "Deleted" },
              ]}
              onChange={(s) => {
                setFilter(s);
                setPage(0);
              }}
              width={240}
            />
            <Button
              label="Refresh"
              variant="ghost"
              onClick={() => {
                void query.refetch();
              }}
            />
          </div>
          <p className="muted small">
            Stored files may still be awaiting verification or indexing. Open a
            document to inspect its full lifecycle.
          </p>
          {query.isPending ? (
            <Loading label="Loading documents" />
          ) : query.error ? (
            <Feedback
              error={query.error}
              retry={() => {
                void query.refetch();
              }}
            />
          ) : query.data.content.length ? (
            <section className="table-panel">
              <Table aria-label="Documents" density="spacious" hasHover>
                <TableHeader>
                  <TableRow>
                    <TableHeaderCell>Document</TableHeaderCell>
                    <TableHeaderCell>Storage</TableHeaderCell>
                    <TableHeaderCell>Revision</TableHeaderCell>
                    <TableHeaderCell>Modified</TableHeaderCell>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {query.data.content.map((d) => (
                    <TableRow key={d.id}>
                      <TableCell>
                        <Link
                          className="table-title"
                          href={`/${org.id}/documents/${d.id}`}
                        >
                          <FileText size={16} aria-hidden="true" />
                          {d.title}
                        </Link>
                        <p className="small muted">
                          {d.currentRevision?.originalFilename ??
                            "No published source"}
                        </p>
                      </TableCell>
                      <TableCell>
                        <Status value={d.status} />
                      </TableCell>
                      <TableCell>
                        <span className="mono">
                          {d.currentRevision?.revisionNumber ?? "—"}
                        </span>
                      </TableCell>
                      <TableCell>
                        <span className="muted">{date(d.modifiedAt)}</span>
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
                filter === "AVAILABLE"
                  ? "Your knowledge base is empty"
                  : "No documents with this status"
              }
              description="Upload a runbook or postmortem to make your engineering knowledge accessible."
            />
          )}
          {upload ? (
            <UploadForm
              onClose={() => setUpload(false)}
              onSaved={(d) => {
                void client.invalidateQueries({
                  queryKey: keys.documents(org.id),
                });
                router.push(`/${org.id}/documents/${d.id}`);
              }}
            />
          ) : null}
        </div>
      </LayoutContent>
    </Layout>
  );
}
function Lifecycle({ document }: { document: Document }) {
  const { organization: org } = useOrganization();
  const revision = document.currentRevision;
  const base = `${tenant(org.id)}/documents/${document.id}/revisions/${revision?.id}`;
  const ingestion = useQuery({
    queryKey: [org.id, "ingestion", document.id, revision?.id],
    queryFn: ({ signal }) => api<Ingestion>(base + "/ingestion", { signal }),
    enabled: !!revision && document.status !== "DELETED",
  });
  const indexing = useQuery({
    queryKey: [org.id, "indexing", document.id, revision?.id],
    queryFn: ({ signal }) => api<Indexing>(base + "/indexing", { signal }),
    enabled: !!revision && document.status !== "DELETED",
  });
  return (
    <section className="panel">
      <div className="section-heading">
        <h2>Source lifecycle</h2>
        <Button
          label="Refresh lifecycle"
          size="sm"
          variant="ghost"
          onClick={() => {
            void ingestion.refetch();
            void indexing.refetch();
          }}
          isDisabled={!revision || document.status === "DELETED"}
        />
      </div>
      <div className="lifecycle">
        <section>
          <h3>Storage</h3>
          <Status value={document.status} />
          <p className="muted small">
            {document.status === "AVAILABLE"
              ? "Original bytes stored; indexing is independent."
              : document.status === "DELETED"
                ? "Tombstoned. Original and historical downloads are unavailable."
                : "Inspect the stored revision attempt."}
          </p>
        </section>
        <section>
          <h3>Verification</h3>
          {!revision || document.status === "DELETED" ? (
            <p className="muted">Unavailable</p>
          ) : ingestion.isPending ? (
            <Loading />
          ) : ingestion.error ? (
            <Feedback error={ingestion.error} />
          ) : (
            <>
              <Status value={ingestion.data.status} />
              <p className="muted small">
                {ingestion.data.status === "VERIFIED"
                  ? "Size and SHA-256 checked."
                  : `Attempts: ${ingestion.data.attempts}`}
              </p>
              {ingestion.data.errorCode ? (
                <p className="mono small">{ingestion.data.errorCode}</p>
              ) : null}
            </>
          )}
        </section>
        <section>
          <h3>Extraction & indexing</h3>
          {!revision || document.status === "DELETED" ? (
            <p className="muted">Unavailable</p>
          ) : indexing.isPending ? (
            <Loading />
          ) : indexing.error ? (
            <Feedback error={indexing.error} />
          ) : (
            <>
              <Status value={indexing.data.status} />
              <p className="muted small">
                {indexing.data.status === "INDEXED"
                  ? `${indexing.data.chunkCount ?? 0} chunks · ${indexing.data.published ? "published" : "not active"}`
                  : (indexing.data.phase ?? "No indexing work scheduled")}
              </p>
              {indexing.data.errorCode ? (
                <p className="mono small">{indexing.data.errorCode}</p>
              ) : null}
              <p className="muted small">
                A failed replacement may leave a previous valid index published.
              </p>
            </>
          )}
        </section>
      </div>
      <p className="muted small">
        Terminal failures require operator inspection; there is no public retry
        or reindex endpoint.
      </p>
    </section>
  );
}
function RenameDocument({
  document,
  onClose,
  onSaved,
  onReload,
}: {
  document: Document;
  onClose: () => void;
  onSaved: (d: Document) => void;
  onReload: () => void;
}) {
  const { organization: org } = useOrganization();
  const [title, setTitle] = useState(document.title);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const [submitted, setSubmitted] = useState(false);
  const valid = !!title.trim() && title.length <= 200;
  async function save() {
    setSubmitted(true);
    if (!valid) return;
    setPending(true);
    try {
      onSaved(
        await writes.renameDocument(
          org.id,
          document.id,
          title,
          document.version,
        ),
      );
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }
  return (
    <Modal title="Rename document" open onClose={pending ? () => {} : onClose}>
      <form
        className="form-stack"
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <TextInput
          label="Document title"
          value={title}
          onChange={setTitle}
          isRequired
          isDisabled={pending}
          status={
            submitted && !valid
              ? { type: "error", message: "Enter a title of 1–200 characters." }
              : undefined
          }
        />
        <Feedback
          error={error}
          retry={
            error instanceof ApiError && error.status === 409
              ? onReload
              : undefined
          }
        />
        <div className="actions end">
          <Button label="Cancel" onClick={onClose} isDisabled={pending} />
          <Button
            label="Save title"
            type="submit"
            variant="primary"
            isLoading={pending}
            isDisabled={error instanceof ApiError && error.status === 409}
          />
        </div>
      </form>
    </Modal>
  );
}
export function DocumentDetail({ id }: { id: string }) {
  const { organization: org } = useOrganization();
  const client = useQueryClient();
  const router = useRouter();
  const [action, setAction] = useState<"rename" | "revise" | "delete" | null>(
    null,
  );
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: [...keys.documents(org.id), id],
    queryFn: ({ signal }) =>
      api<Document>(`${tenant(org.id)}/documents/${id}`, { signal }),
  });
  const history = useQuery({
    queryKey: [org.id, "revisions", id, page],
    queryFn: ({ signal }) =>
      api<DocumentPage<Revision>>(
        `${tenant(org.id)}/documents/${id}/revisions?page=${page}&size=20`,
        { signal },
      ),
    enabled:
      query.data?.status !== undefined && query.data.status !== "DELETED",
  });
  const d = query.data;
  function saved(document: Document) {
    setAction(null);
    client.setQueryData([...keys.documents(org.id), id], document);
    void client.invalidateQueries({ queryKey: [org.id] });
  }
  function reload() {
    setAction(null);
    setError(undefined);
    void query.refetch();
    void history.refetch();
  }
  async function remove() {
    if (!d) return;
    setPending(true);
    setError(undefined);
    try {
      await writes.deleteDocument(org.id, id, d.version);
      void client.invalidateQueries({ queryKey: [org.id] });
      router.push(`/${org.id}/documents`);
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }
  async function getFile(rev?: Revision) {
    if (!d) return;
    setError(undefined);
    try {
      await download(
        org.id,
        id,
        rev?.id,
        rev?.originalFilename ??
          d.currentRevision?.originalFilename ??
          "document",
      );
    } catch (e) {
      setError(e);
    }
  }
  return (
    <Layout height="auto" padding={6}>
      <LayoutContent padding={6}>
        <div className="page">
          <Button
            label="Knowledge Base"
            icon={<ArrowLeft size={16} />}
            width="fit-content"
            href={`/${org.id}/documents`}
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
          ) : d ? (
            <>
              <PageHeader
                title={d.title}
                description={`Modified ${date(d.modifiedAt)} · metadata version ${d.version}`}
                actions={
                  d.status !== "DELETED" ? (
                    <>
                      <Button
                        label="Download source"
                        icon={<Download size={16} />}
                        onClick={() => {
                          void getFile();
                        }}
                        isDisabled={
                          !d.currentRevision ||
                          d.currentRevision.status !== "AVAILABLE"
                        }
                      />
                      {org.role !== "VIEWER" ? (
                        <>
                          <Button
                            label="Rename"
                            variant="ghost"
                            onClick={() => setAction("rename")}
                          />
                          <Button
                            label="New revision"
                            variant="primary"
                            onClick={() => setAction("revise")}
                            isDisabled={d.status === "UPLOADING"}
                          />
                          <Button
                            label="Delete document"
                            variant="ghost"
                            onClick={() => {
                              setError(undefined);
                              setAction("delete");
                            }}
                          />
                        </>
                      ) : null}
                    </>
                  ) : null
                }
              />
              <Feedback
                error={error}
                retry={
                  error instanceof ApiError && error.status === 409
                    ? reload
                    : undefined
                }
              />
              <Lifecycle document={d} />
              {d.status === "DELETED" ? (
                <Empty
                  title="Document unavailable"
                  description="Deletion is a tombstone, not physical erasure. Source downloads and historical citations are denied."
                />
              ) : (
                <section className="panel">
                  <h2>Revision history</h2>
                  <p className="muted small">
                    Immutable source attempts. Previous available revisions can
                    be downloaded until the document is deleted.
                  </p>
                  {history.isPending ? (
                    <Loading />
                  ) : history.error ? (
                    <Feedback
                      error={history.error}
                      retry={() => {
                        void history.refetch();
                      }}
                    />
                  ) : history.data.content.length ? (
                    <>
                      <Table aria-label="Revision history" density="spacious">
                        <TableHeader>
                          <TableRow>
                            <TableHeaderCell>Revision</TableHeaderCell>
                            <TableHeaderCell>File</TableHeaderCell>
                            <TableHeaderCell>Storage</TableHeaderCell>
                            <TableHeaderCell>Size</TableHeaderCell>
                            <TableHeaderCell>Action</TableHeaderCell>
                          </TableRow>
                        </TableHeader>
                        <TableBody>
                          {history.data.content.map((r) => (
                            <TableRow key={r.id}>
                              <TableCell>
                                <span className="mono">{r.revisionNumber}</span>
                                {r.id === d.currentRevision?.id ? (
                                  <p className="muted small">Current</p>
                                ) : null}
                              </TableCell>
                              <TableCell>
                                <strong>{r.originalFilename}</strong>
                                <p className="muted small">
                                  {date(r.createdAt)}
                                </p>
                              </TableCell>
                              <TableCell>
                                <Status value={r.status} />
                              </TableCell>
                              <TableCell>
                                {(r.fileSize / 1024).toFixed(1)} KiB
                              </TableCell>
                              <TableCell>
                                <Button
                                  label={`Download revision ${r.revisionNumber}`}
                                  size="sm"
                                  variant="ghost"
                                  onClick={() => {
                                    void getFile(r);
                                  }}
                                  isDisabled={r.status !== "AVAILABLE"}
                                />
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                      <Pager
                        page={page}
                        total={history.data.totalElements}
                        totalPages={history.data.totalPages}
                        onChange={setPage}
                      />
                    </>
                  ) : (
                    <Empty
                      title="No revisions"
                      description="This document has no visible source attempts."
                    />
                  )}
                </section>
              )}
              {action === "rename" ? (
                <RenameDocument
                  document={d}
                  onClose={() => setAction(null)}
                  onSaved={saved}
                  onReload={reload}
                />
              ) : action === "revise" ? (
                <UploadForm
                  document={d}
                  onClose={() => setAction(null)}
                  onSaved={saved}
                  onReload={reload}
                />
              ) : action === "delete" ? (
                <Confirm
                  title="Delete document?"
                  description="This makes all revisions unavailable and invalidates dependent AI answers. Original content is retained privately; no restore API is available."
                  pending={pending}
                  onReload={reload}
                  onConfirm={() => {
                    void remove();
                  }}
                  onClose={() => setAction(null)}
                  error={error}
                />
              ) : null}
            </>
          ) : null}
        </div>
      </LayoutContent>
    </Layout>
  );
}
