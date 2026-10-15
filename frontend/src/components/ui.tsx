"use client";
import { useState, useEffect, type ReactNode } from "react";
import { Button } from "@astryxdesign/core/Button";
import { Banner } from "@astryxdesign/core/Banner";
import { Dialog, DialogHeader } from "@astryxdesign/core/Dialog";
import { EmptyState } from "@astryxdesign/core/EmptyState";
import { StatusDot } from "@astryxdesign/core/StatusDot";
import { LoaderCircle } from "lucide-react";
import { ApiError, errorText } from "@/lib/api";
export function Loading({ label = "Loading" }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <LoaderCircle className="spin" aria-hidden="true" />
      {label}…
    </div>
  );
}
export function Feedback({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  return error ? (
    <div role="alert">
      <Banner
        status={
          error instanceof ApiError && error.status === 409
            ? "warning"
            : "error"
        }
        title={errorText(error)}
        description={
          error instanceof ApiError && error.code !== "REQUEST_FAILED"
            ? error.code.replaceAll("_", " ").toLowerCase()
            : undefined
        }
        endContent={
          retry ? (
            <Button
              label={
                error instanceof ApiError && error.status === 409
                  ? "Reload latest"
                  : "Try again"
              }
              onClick={retry}
            />
          ) : undefined
        }
      />
    </div>
  ) : null;
}
export function Empty({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <EmptyState
      title={title}
      description={description}
      actions={action}
      headingLevel={2}
    />
  );
}
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description: string;
  actions?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div>
        <p className="breadcrumbs">
          Workspace <span aria-hidden="true">/</span> {title}
        </p>
        <h1>{title}</h1>
        <p className="muted">{description}</p>
      </div>
      <div className="actions">{actions}</div>
    </header>
  );
}
export function Status({ value }: { value: string }) {
  const good = ["RESOLVED", "CLOSED", "VERIFIED", "INDEXED"];
  const bad = ["OPEN", "FAILED", "DELETED", "REDACTED"];
  return (
    <span className="status-label">
      <StatusDot
        label={value.replaceAll("_", " ").toLowerCase()}
        variant={
          good.includes(value)
            ? "success"
            : bad.includes(value)
              ? "error"
              : value === "AVAILABLE"
                ? "neutral"
                : "warning"
        }
      />
      {value.replaceAll("_", " ").toLowerCase()}
    </span>
  );
}
export function Pager({
  page,
  total,
  totalPages,
  onChange,
}: {
  page: number;
  total: number;
  totalPages: number;
  onChange: (page: number) => void;
}) {
  return (
    <footer className="pagination">
      <span className="muted small">
        {total} {total === 1 ? "record" : "records"} · Page {page + 1} of{" "}
        {Math.max(1, totalPages)}
      </span>
      <div className="actions">
        <Button
          label="Previous"
          size="sm"
          isDisabled={page === 0}
          onClick={() => onChange(page - 1)}
        />
        <Button
          label="Next"
          size="sm"
          isDisabled={page + 1 >= totalPages}
          onClick={() => onChange(page + 1)}
        />
      </div>
    </footer>
  );
}
export function Modal({
  title,
  open,
  onClose,
  children,
}: {
  title: string;
  open: boolean;
  onClose: () => void;
  children: ReactNode;
}) {
  const [opener] = useState(() =>
    typeof document === "undefined"
      ? null
      : (document.activeElement as HTMLElement | null),
  );
  useEffect(
    () => () => {
      queueMicrotask(() => {
        if (opener?.isConnected) opener.focus();
      });
    },
    [opener],
  );
  return (
    <Dialog
      isOpen={open}
      onOpenChange={(v) => {
        if (!v) onClose();
      }}
      purpose="form"
      width={560}
      aria-label={title}
    >
      <DialogHeader title={title} />
      {children}
    </Dialog>
  );
}
export function Confirm({
  title,
  description,
  pending,
  onConfirm,
  onClose,
  error,
  onReload,
}: {
  title: string;
  description: string;
  pending: boolean;
  onConfirm: () => void;
  onClose: () => void;
  error?: unknown;
  onReload?: () => void;
}) {
  return (
    <Modal title={title} open onClose={pending ? () => {} : onClose}>
      <div className="form-stack">
        <p>{description}</p>
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
            label="Confirm deletion"
            isDisabled={error instanceof ApiError && error.status === 409}
            variant="destructive"
            isLoading={pending}
            onClick={onConfirm}
          />
        </div>
      </div>
    </Modal>
  );
}
export const date = (value: string) =>
  new Date(value).toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
export function SourceLocation({
  section,
  pageStart,
  pageEnd,
}: {
  section: string | null;
  pageStart: number | null;
  pageEnd: number | null;
}) {
  return (
    <span className="muted small">
      {pageStart
        ? `Page ${pageStart}${pageEnd && pageEnd !== pageStart ? `–${pageEnd}` : ""}`
        : section || "Document passage"}
    </span>
  );
}
