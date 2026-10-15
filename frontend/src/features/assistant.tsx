"use client";
import { useEffect, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { TextArea } from "@astryxdesign/core/TextArea";
import { Layout, LayoutContent } from "@astryxdesign/core/Layout";
import { ChatMessage, ChatMessageBubble } from "@astryxdesign/core/Chat";
import { Plus, Send, MessagesSquare } from "lucide-react";
import { useOrganization } from "@/components/workspace";
import { SafeMarkdown } from "@/components/safe-markdown";
import { api, ApiError, tenant, keys, writes, byteLength } from "@/lib/api";
import {
  replayKey,
  readReplay,
  saveReplay,
  removeReplay,
  subscribeReplay,
} from "@/lib/pending-turn";
import type {
  Conversation,
  ConversationPage,
  History,
  Message,
  Citation,
} from "@/lib/contracts";
import {
  PageHeader,
  Feedback,
  Loading,
  Empty,
  Pager,
  Modal,
  Confirm,
  SourceLocation,
  date,
} from "@/components/ui";
function ConversationTitle({
  conversation,
  onClose,
  onSaved,
}: {
  conversation?: Conversation;
  onClose: () => void;
  onSaved: (c: Conversation) => void;
}) {
  const { organization: org } = useOrganization();
  const [title, setTitle] = useState(conversation?.title ?? "");
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const valid =
    !!title.trim() && title.length <= 120 && !/[\u0000-\u001f]/.test(title);
  async function save() {
    setSubmitted(true);
    if (!valid) return;
    setPending(true);
    try {
      onSaved(
        conversation
          ? await writes.renameConversation(org.id, conversation.id, title)
          : await writes.createConversation(org.id, title),
      );
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }
  return (
    <Modal
      title={conversation ? "Rename conversation" : "New conversation"}
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
          label="Conversation title"
          value={title}
          onChange={setTitle}
          isRequired
          status={
            submitted && !valid
              ? {
                  type: "error",
                  message:
                    "Use a title of 1–120 characters without control characters.",
                }
              : undefined
          }
        />
        <Feedback error={error} />
        <div className="actions end">
          <Button label="Cancel" isDisabled={pending} onClick={onClose} />
          <Button
            label={conversation ? "Save title" : "Create conversation"}
            variant="primary"
            type="submit"
            isLoading={pending}
          />
        </div>
      </form>
    </Modal>
  );
}
function CitationDialog({
  conversation,
  message,
  citation,
  onClose,
}: {
  conversation: string;
  message: string;
  citation: Citation;
  onClose: () => void;
}) {
  const { organization: org } = useOrganization();
  const client = useQueryClient();
  const resolved = useQuery({
    queryKey: [org.id, "citation", conversation, message, citation.reference],
    queryFn: ({ signal }) =>
      api<Citation>(
        `${tenant(org.id)}/conversations/${conversation}/messages/${message}/citations/${citation.reference}`,
        { signal },
      ),
    gcTime: 0,
  });
  useEffect(() => {
    if (
      resolved.error instanceof ApiError &&
      [403, 404].includes(resolved.error.status)
    )
      void client.invalidateQueries({
        queryKey: keys.history(org.id, conversation),
      });
  }, [resolved.error, client, org.id, conversation]);
  // Ignore the cached excerpt; only the live exact-source resolver may supply this dialog.
  return (
    <Modal title={`Source ${citation.reference}`} open onClose={onClose}>
      <div className="form-stack">
        {resolved.isFetching ? (
          <Loading label="Checking current source access" />
        ) : resolved.error ? (
          <Feedback error={resolved.error} />
        ) : resolved.data ? (
          <>
            <h3>{resolved.data.title}</h3>
            <SourceLocation
              section={resolved.data.section}
              pageStart={resolved.data.pageStart}
              pageEnd={resolved.data.pageEnd}
            />
            <blockquote className="pre-wrap">
              {resolved.data.excerpt}
            </blockquote>
            <p className="small muted">
              This passage is resolved from the currently authorized source.
              Citation validity establishes provenance, not that every claim is
              supported.
            </p>
            <Button
              label="Open source document"
              href={`/${org.id}/documents/${resolved.data.documentId}`}
              onClick={onClose}
            />
          </>
        ) : null}
        <Button label="Close" variant="ghost" onClick={onClose} />
      </div>
    </Modal>
  );
}
function Elapsed({ since }: { since: string }) {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    const tick = () =>
      setSeconds(
        Math.max(0, Math.floor((Date.now() - Date.parse(since)) / 1000)),
      );
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [since]);
  return (
    <span className="muted small">
      {seconds}s elapsed · generation can take around two minutes
    </span>
  );
}
function Thread({ id }: { id: string }) {
  const { organization: org, session } = useOrganization();
  const client = useQueryClient();
  const [page, setPage] = useState(0);
  const [content, setContent] = useState("");
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const [submittedContent, setSubmittedContent] = useState<string | null>(null);
  const [citation, setCitation] = useState<{
    message: string;
    citation: Citation;
  }>();
  const [rename, setRename] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deletePending, setDeletePending] = useState(false);
  const router = useRouter();
  const handle = replayKey(
    `${session.identity.issuer}:${session.identity.subject}`,
    org.id,
    id,
  );
  const replay = useSyncExternalStore(
    subscribeReplay,
    () => readReplay(handle),
    () => null,
  );
  const setReplay = (value: string | null) => {
    if (value) saveReplay(handle, value);
    else removeReplay(handle);
  };
  const history = useQuery({
    queryKey: [...keys.history(org.id, id), page],
    queryFn: ({ signal }) =>
      api<History>(
        `${tenant(org.id)}/conversations/${id}?page=${page}&size=20`,
        { signal },
      ),
    refetchInterval: (query) =>
      pending ||
      query.state.data?.messages.some((m) => m.status === "GENERATING")
        ? 5000
        : false,
    refetchIntervalInBackground: false,
  });
  const active = history.data?.messages.find(
    (m) => m.role === "ASSISTANT" && m.status === "GENERATING",
  );
  const terminal = history.data?.messages
    .filter((m) => m.role === "ASSISTANT")
    .at(-1);
  const pair = history.data?.messages.find(
    (m) => m.role === "USER" && m.turnId === (active ?? terminal)?.turnId,
  );
  const overLimit = byteLength(content) > session.maxQuestionBytes;
  async function submit(recover = false, explicitQuestion?: string) {
    const question = recover
      ? (submittedContent ?? pair?.content)
      : (explicitQuestion ?? content.trim());
    const key = recover ? replay : crypto.randomUUID();
    if (
      !question ||
      !key ||
      byteLength(question) > session.maxQuestionBytes ||
      pending
    )
      return;
    setPending(true);
    setError(undefined);
    setSubmittedContent(question);
    setReplay(key);
    try {
      const result = await writes.send(org.id, id, key, question);
      if (result.status !== "GENERATING") {
        removeReplay(handle);
        setReplay(null);
        setSubmittedContent(null);
        setContent("");
      }
      await refreshLatest();
    } catch (e) {
      setError(e);
      await refreshLatest();
    } finally {
      setPending(false);
    }
  }
  async function refreshLatest() {
    const first = await history.refetch();
    if (first.data) {
      const lastPage = Math.max(
        0,
        Math.ceil(first.data.totalElements / 20) - 1,
      );
      setPage(lastPage);
    }
    void client.invalidateQueries({ queryKey: keys.conversations(org.id) });
  }
  async function remove() {
    setDeletePending(true);
    try {
      await writes.deleteConversation(org.id, id);
      removeReplay(handle);
      void client.invalidateQueries({ queryKey: keys.conversations(org.id) });
      client.removeQueries({ queryKey: keys.history(org.id, id) });
      router.push(`/${org.id}/assistant`);
    } catch (e) {
      setError(e);
    } finally {
      setDeletePending(false);
    }
  }
  function renderMessage(m: Message) {
    return (
      <article
        key={m.id}
        className={`message ${m.role.toLowerCase()}`}
        aria-label={m.role === "USER" ? "Your message" : "Assistant response"}
      >
        <ChatMessage sender={m.role === "USER" ? "user" : "assistant"}>
          <ChatMessageBubble variant={m.role === "USER" ? "filled" : "ghost"}>
            <div className="message-label">
              {m.role === "USER" ? "You" : "Traceback"}{" "}
              <span className="muted small">{date(m.createdAt)}</span>
            </div>
            {m.role === "USER" ? (
              <p className="pre-wrap">{m.content}</p>
            ) : m.status === "GENERATING" ? (
              <>
                <Loading label="Investigating your sources" />
                <Elapsed since={m.createdAt} />
              </>
            ) : m.status === "FAILED" ? (
              <div>
                <p>This turn did not produce an answer.</p>
                <p className="muted small">
                  {m.errorCode?.replaceAll("_", " ").toLowerCase()}
                </p>
              </div>
            ) : m.status === "REDACTED" ? (
              <p className="redacted">
                This answer is unavailable because its evidence or citation
                format is no longer current or valid.
              </p>
            ) : (
              <>
                <SafeMarkdown content={m.content ?? ""} />
                {m.status === "INSUFFICIENT_EVIDENCE" ? (
                  <p className="muted small">
                    No supported answer was found in authorized sources. Try a
                    narrower question or add relevant documents.
                  </p>
                ) : null}
                <div className="citations">
                  {m.citations.map((c) => (
                    <Button
                      key={c.reference}
                      label={`${c.reference} · ${c.title}`}
                      variant="secondary"
                      size="sm"
                      onClick={() =>
                        setCitation({ message: m.id, citation: c })
                      }
                    />
                  ))}
                </div>
              </>
            )}
          </ChatMessageBubble>
        </ChatMessage>
      </article>
    );
  }
  return (
    <section className="thread">
      <div className="thread-header">
        <div>
          <h2>{history.data?.conversation.title ?? "Conversation"}</h2>
          <p className="muted small">
            Private investigation · answers cite current sources
          </p>
        </div>
        <div className="actions">
          <Button
            label="Refresh history"
            size="sm"
            variant="ghost"
            onClick={() => {
              void refreshLatest();
            }}
          />
          <Button
            label="Rename"
            size="sm"
            variant="ghost"
            onClick={() => setRename(true)}
            isDisabled={!history.data}
          />
          <Button
            label="Delete conversation"
            size="sm"
            variant="ghost"
            onClick={() => setDeleting(true)}
          />
        </div>
      </div>
      <Feedback error={error} />
      {history.error ? (
        <Feedback
          error={history.error}
          retry={() => {
            void history.refetch();
          }}
        />
      ) : history.isPending ? (
        <Loading label="Loading conversation" />
      ) : history.isFetching && !pending && !active ? (
        <Loading label="Checking current evidence" />
      ) : history.data ? (
        <>
          <div className="message-list">
            {history.data.messages.length ? (
              history.data.messages.map(renderMessage)
            ) : (
              <Empty
                title="Start with a question"
                description="Ask about an error, a runbook, or an incident. Answers use authorized indexed knowledge."
              />
            )}
          </div>
          <Pager
            page={page}
            total={history.data.totalElements}
            totalPages={Math.ceil(history.data.totalElements / 20)}
            onChange={setPage}
          />
        </>
      ) : null}
      {pending && !active ? (
        <Loading label="Waiting for the durable turn response" />
      ) : null}
      {replay && !pending ? (
        <div className="recovery">
          <p className="small">
            Check the saved turn with the same key. This does not create another
            user message.
          </p>
          <Button
            label="Check saved turn"
            onClick={() => {
              void submit(true);
            }}
            isDisabled={!submittedContent && !pair?.content}
          />
          <Button
            label="Dismiss saved retry"
            variant="ghost"
            onClick={() => {
              removeReplay(handle);
              setReplay(null);
              setSubmittedContent(null);
            }}
          />
        </div>
      ) : null}
      {active &&
      !replay &&
      Date.now() - Date.parse(active.createdAt) > 180000 ? (
        <div className="recovery">
          <p className="small">
            The saved reservation appears stalled and its original replay key is
            unavailable. A new attempt deliberately creates another user turn;
            the backend must first recover the expired reservation.
          </p>
          <Button
            label="Start new attempt"
            onClick={() => {
              void submit(false, pair?.content ?? undefined);
            }}
            isDisabled={pending || !pair?.content}
          />
        </div>
      ) : null}
      <form
        className="composer"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <TextArea
          label="Message"
          value={content}
          onChange={setContent}
          rows={3}
          placeholder="What happened, and what does the evidence suggest?"
          isDisabled={pending || !!active || !!replay || !!history.error}
          description={`Maximum ${session.maxQuestionBytes} UTF-8 bytes. No automatic regeneration.`}
          status={
            overLimit
              ? {
                  type: "error",
                  message: `Message is ${byteLength(content)} bytes; shorten it before sending.`,
                }
              : undefined
          }
        />
        <div className="composer-footer">
          <p className="muted small">
            Review the cited sources. AI answers can be wrong.
          </p>
          <Button
            label="Send message"
            icon={<Send size={16} />}
            variant="primary"
            type="submit"
            isLoading={pending}
            isDisabled={
              !content.trim() ||
              overLimit ||
              !!active ||
              !!replay ||
              !!history.error
            }
          />
        </div>
      </form>
      {citation ? (
        <CitationDialog
          conversation={id}
          message={citation.message}
          citation={citation.citation}
          onClose={() => setCitation(undefined)}
        />
      ) : null}
      {rename && history.data ? (
        <ConversationTitle
          conversation={history.data.conversation}
          onClose={() => setRename(false)}
          onSaved={() => {
            setRename(false);
            void client.invalidateQueries({ queryKey: [org.id] });
          }}
        />
      ) : null}
      {deleting ? (
        <Confirm
          title="Delete conversation?"
          description="This makes the conversation and all its messages unavailable, including any active generation. There is no restore API."
          pending={deletePending}
          onConfirm={() => {
            void remove();
          }}
          onClose={() => setDeleting(false)}
          error={error}
        />
      ) : null}
    </section>
  );
}
export function Assistant({ id }: { id?: string }) {
  const { organization: org } = useOrganization();
  const client = useQueryClient();
  const router = useRouter();
  const [page, setPage] = useState(0);
  const [create, setCreate] = useState(false);
  const list = useQuery({
    queryKey: [...keys.conversations(org.id), page],
    queryFn: ({ signal }) =>
      api<ConversationPage>(
        `${tenant(org.id)}/conversations?page=${page}&size=20`,
        { signal },
      ),
  });
  return (
    <Layout height="auto" padding={6}>
      <LayoutContent padding={6}>
        <div className="page assistant-page">
          <PageHeader
            title="AI Assistant"
            description="Follow the evidence across your engineering knowledge."
            actions={
              <Button
                label="New conversation"
                icon={<Plus size={16} />}
                variant="primary"
                onClick={() => setCreate(true)}
              />
            }
          />
          <div className="assistant-grid">
            <aside
              className="conversation-list"
              aria-label="Your conversations"
            >
              <h2>Conversations</h2>
              {list.isPending ? (
                <Loading />
              ) : list.error ? (
                <Feedback
                  error={list.error}
                  retry={() => {
                    void list.refetch();
                  }}
                />
              ) : list.data.items.length ? (
                <>
                  <nav aria-label="Conversation history">
                    {list.data.items.map((c) => (
                      <Link
                        key={c.id}
                        className={`conversation-link ${c.id === id ? "selected" : ""}`}
                        aria-current={c.id === id ? "page" : undefined}
                        href={`/${org.id}/assistant/${c.id}`}
                      >
                        <MessagesSquare size={16} aria-hidden="true" />
                        <span>{c.title}</span>
                      </Link>
                    ))}
                  </nav>
                  <Pager
                    page={page}
                    total={list.data.totalElements}
                    totalPages={Math.ceil(list.data.totalElements / 20)}
                    onChange={setPage}
                  />
                </>
              ) : (
                <p className="muted small">
                  Your private conversations will appear here.
                </p>
              )}
            </aside>
            {id ? (
              <Thread key={id} id={id} />
            ) : (
              <section className="assistant-empty">
                <MessagesSquare size={40} aria-hidden="true" />
                <Empty
                  title="An investigation starts with a question"
                  description="Explore your indexed knowledge, connect past incidents, and inspect the sources behind each answer."
                  action={
                    <Button
                      label="Start a conversation"
                      variant="primary"
                      onClick={() => setCreate(true)}
                    />
                  }
                />
              </section>
            )}
          </div>
          {create ? (
            <ConversationTitle
              onClose={() => setCreate(false)}
              onSaved={(c) => {
                setCreate(false);
                void client.invalidateQueries({
                  queryKey: keys.conversations(org.id),
                });
                router.push(`/${org.id}/assistant/${c.id}`);
              }}
            />
          ) : null}
        </div>
      </LayoutContent>
    </Layout>
  );
}
