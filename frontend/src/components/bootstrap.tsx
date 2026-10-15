"use client";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { api, writes, errorText } from "@/lib/api";
import type { Organization } from "@/lib/contracts";
import { Feedback, Loading } from "./ui";
export function Bootstrap() {
  const router = useRouter();
  const [organizations, setOrganizations] = useState<Organization[]>();
  const [name, setName] = useState("");
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  useEffect(() => {
    const abort = new AbortController();
    api<Organization[]>("organizations", { signal: abort.signal })
      .then((list) => {
        setOrganizations(list);
        if (list.length) router.replace(`/${list[0].id}/overview`);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(e);
      });
    return () => abort.abort();
  }, [router]);
  async function create() {
    if (!name.trim() || name.length > 120) return;
    setPending(true);
    setError(undefined);
    try {
      const org = await writes.createOrganization(name);
      router.replace(`/${org.id}/overview`);
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }
  if (error)
    return (
      <main className="bootstrap">
        <Feedback error={error} retry={() => location.reload()} />
      </main>
    );
  if (!organizations || organizations.length)
    return (
      <main className="bootstrap">
        <Loading label="Opening your workspace" />
      </main>
    );
  return (
    <main className="bootstrap">
      <h1>Your first workspace</h1>
      <p className="muted">
        Create an organization to begin recording incidents and building shared
        engineering knowledge. You will be its administrator.
      </p>
      <form
        className="form-stack"
        onSubmit={(e) => {
          e.preventDefault();
          void create();
        }}
      >
        <TextInput
          label="Organization name"
          value={name}
          onChange={setName}
          isRequired
        />
        <Button
          label="Create organization"
          variant="primary"
          type="submit"
          isLoading={pending}
        />
      </form>
      <p className="muted small">Member invitations are not available yet.</p>
      <span className="sr-only">{error ? errorText(error) : ""}</span>
    </main>
  );
}
