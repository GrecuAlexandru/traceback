"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { Selector } from "@astryxdesign/core/Selector";
import { Banner } from "@astryxdesign/core/Banner";
import { Layout, LayoutContent } from "@astryxdesign/core/Layout";
import { Search } from "lucide-react";
import { useOrganization } from "@/components/workspace";
import { api, tenant } from "@/lib/api";
import type { SearchResponse, SearchMode } from "@/lib/contracts";
import {
  PageHeader,
  Feedback,
  Loading,
  Empty,
  SourceLocation,
} from "@/components/ui";
export function KnowledgeSearch() {
  const { organization: org } = useOrganization();
  const [input, setInput] = useState("");
  const [mode, setMode] = useState<SearchMode>("hybrid");
  const [submission, setSubmission] = useState<{
    q: string;
    mode: SearchMode;
    nonce: number;
  }>();
  const [invalid, setInvalid] = useState(false);
  const result = useQuery({
    queryKey: [org.id, "search", submission],
    queryFn: ({ signal }) =>
      api<SearchResponse>(
        `${tenant(org.id)}/search?${new URLSearchParams({ q: submission!.q, mode: submission!.mode, limit: "10" })}`,
        { signal },
      ),
    enabled: !!submission,
    gcTime: 0,
  });
  function search() {
    const q = input.trim();
    if (
      !q ||
      [...q].length > 256 ||
      q.split(/[^\p{L}\p{N}_]+/u).filter(Boolean).length > 16 ||
      /[\u0000-\u001f]/.test(q)
    ) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    setSubmission({ q, mode, nonce: Date.now() });
  }
  return (
    <Layout height="auto" padding={6}>
      <LayoutContent padding={6}>
        <div className="page search-page">
          <PageHeader
            title="Search"
            description="Find the runbook, error message, or source that connects the dots."
          />
          <form
            className="search-form"
            onSubmit={(e) => {
              e.preventDefault();
              search();
            }}
          >
            <TextInput
              label="Search engineering knowledge"
              value={input}
              onChange={setInput}
              placeholder="An exact error, system, or investigation question"
              startIcon={<Search size={16} />}
              width="100%"
              status={
                invalid
                  ? {
                      type: "error",
                      message:
                        "Use 1–256 code points, at most 16 terms, and no control characters.",
                    }
                  : undefined
              }
            />
            <Selector
              label="Search mode"
              value={mode}
              onChange={(v) => setMode(v as SearchMode)}
              options={[
                { value: "keyword", label: "Keyword" },
                { value: "semantic", label: "Semantic" },
                { value: "hybrid", label: "Hybrid" },
              ]}
            />
            <Button
              label="Search"
              type="submit"
              variant="primary"
              isLoading={result.isFetching}
            />
          </form>
          <p className="muted small">
            Keyword matches exact terms. Semantic finds related meaning. Hybrid
            combines their ranks. Search runs only when you submit.
          </p>
          {!submission ? (
            <Empty
              title="Your knowledge, within reach"
              description="Search indexed sources in the selected organization. Exact technical identifiers work well with keyword search."
            />
          ) : result.isFetching ? (
            <Loading label="Searching authorized sources" />
          ) : result.error ? (
            <Feedback
              error={result.error}
              retry={() => {
                void result.refetch();
              }}
            />
          ) : result.data ? (
            <>
              <div className="section-heading">
                <h2>
                  {result.data.returnedCount}{" "}
                  {result.data.returnedCount === 1 ? "passage" : "passages"}
                </h2>
                <span className="muted small">
                  {result.data.effectiveMode} results
                </span>
              </div>
              {result.data.degradationCode ? (
                <Banner
                  status="warning"
                  title="Showing keyword results"
                  description="Semantic retrieval is unavailable. These results use keyword matching only."
                />
              ) : null}
              {result.data.results.length ? (
                <ol className="search-results">
                  {result.data.results.map((r, index) => (
                    <li key={`${r.source.generationId}:${r.source.chunkId}`}>
                      <span className="result-rank mono">{index + 1}</span>
                      <article>
                        <Link
                          className="result-title"
                          href={`/${org.id}/documents/${r.source.documentId}`}
                        >
                          {r.source.title}
                        </Link>
                        <SourceLocation
                          section={r.source.section}
                          pageStart={r.source.pageStart}
                          pageEnd={r.source.pageEnd}
                        />
                        <p className="pre-wrap">{r.source.excerpt}</p>
                        <Link
                          className="small"
                          href={`/${org.id}/documents/${r.source.documentId}`}
                        >
                          Open source document →
                        </Link>
                      </article>
                    </li>
                  ))}
                </ol>
              ) : (
                <Empty
                  title="No matching sources"
                  description="Try a shorter query, a different mode, or check that your source documents are indexed."
                />
              )}
            </>
          ) : null}
        </div>
      </LayoutContent>
    </Layout>
  );
}
