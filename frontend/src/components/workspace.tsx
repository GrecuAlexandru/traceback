"use client";
import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { useParams, useRouter } from "next/navigation";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AppShell } from "@astryxdesign/core/AppShell";
import {
  SideNav,
  SideNavHeading,
  SideNavItem,
  SideNavSection,
} from "@astryxdesign/core/SideNav";
import { Selector } from "@astryxdesign/core/Selector";
import { Button } from "@astryxdesign/core/Button";
import { DropdownMenu } from "@astryxdesign/core/DropdownMenu";
import {
  Braces,
  LayoutDashboard,
  TriangleAlert,
  BookOpen,
  Search,
  MessagesSquare,
  Settings,
  SunMoon,
  LogOut,
} from "lucide-react";
import type { Organization, Session } from "@/lib/contracts";
import { api, ApiError } from "@/lib/api";
import { Feedback, Loading } from "./ui";
import { clearReplays } from "@/lib/pending-turn";
import { useAppearance } from "./providers";
type Context = { organization: Organization; session: Session };
const OrganizationContext = createContext<Context | null>(null);
export function useOrganization() {
  const ctx = useContext(OrganizationContext);
  if (!ctx) throw new Error("Organization context required");
  return ctx;
}
const destinations = [
  ["overview", "Overview", LayoutDashboard],
  ["incidents", "Incidents", TriangleAlert],
  ["documents", "Knowledge Base", BookOpen],
  ["search", "Search", Search],
  ["assistant", "AI Assistant", MessagesSquare],
  ["settings", "Settings", Settings],
] as const;
function TenantBoundary({
  context,
  children,
}: {
  context: Context;
  children: ReactNode;
}) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 0,
            gcTime: 0,
            retry: (attempt, error) =>
              attempt < 1 &&
              error instanceof ApiError &&
              [502, 503, 504].includes(error.status),
            refetchOnWindowFocus: true,
          },
          mutations: { retry: false },
        },
      }),
  );
  useEffect(
    () => () => {
      void client.cancelQueries();
      client.clear();
      clearReplays();
    },
    [client],
  );
  return (
    <QueryClientProvider client={client}>
      <OrganizationContext.Provider value={context}>
        {children}
      </OrganizationContext.Provider>
    </QueryClientProvider>
  );
}
export function Workspace({ children }: { children: ReactNode }) {
  const params = useParams<{ organizationId: string; screen?: string[] }>();
  const router = useRouter();
  const { mode, setMode } = useAppearance();
  const [session, setSession] = useState<Session>();
  const [orgs, setOrgs] = useState<Organization[]>();
  const [error, setError] = useState<unknown>();
  const [expired, setExpired] = useState(false);
  useEffect(() => {
    const abort = new AbortController();
    function expire() {
      clearReplays();
      setSession(undefined);
      setOrgs(undefined);
      setExpired(true);
      router.replace("/login?error=session_expired");
      router.refresh();
    }
    async function load() {
      try {
        const response = await fetch("/api/session", {
          cache: "no-store",
          signal: abort.signal,
        });
        if (response.status === 401) {
          expire();
          return;
        }
        if (!response.ok) throw new ApiError(response.status);
        setSession(await response.json());
        setOrgs(
          await api<Organization[]>("organizations", { signal: abort.signal }),
        );
        setError(undefined);
      } catch (e) {
        if (!abort.signal.aborted) setError(e);
      }
    }
    void load();
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") void load();
    }, 60000);
    function visible() {
      if (document.visibilityState === "visible") void load();
    }
    window.addEventListener("traceback:expired", expire);
    document.addEventListener("visibilitychange", visible);
    return () => {
      abort.abort();
      clearInterval(timer);
      window.removeEventListener("traceback:expired", expire);
      document.removeEventListener("visibilitychange", visible);
    };
  }, [router]);
  if (expired) return <Loading label="Returning to sign-in" />;
  if (error)
    return (
      <main className="bootstrap">
        <Feedback error={error} retry={() => location.reload()} />
        <Button label="Sign in" href="/login" />
      </main>
    );
  if (!session || !orgs)
    return (
      <main className="bootstrap">
        <Loading label="Loading your memberships" />
      </main>
    );
  const org = orgs.find((o) => o.id === params.organizationId);
  if (!org)
    return (
      <main className="bootstrap">
        <Feedback error={new ApiError(403)} />
        <Button label="Choose a workspace" href="/" />
      </main>
    );
  async function logout() {
    clearReplays();
    setExpired(true);
    setOrgs(undefined);
    setSession(undefined);
    try {
      const response = await fetch("/api/auth/logout", {
        method: "POST",
        headers: { "X-Traceback-Request": "1" },
      });
      if (!response.ok) throw new Error();
      const data = await response.json();
      location.assign(data.logoutUrl);
    } catch {
      location.assign("/login?error=session_expired");
    }
  }
  const sideNav = (
    <SideNav
      aria-label="Workspace navigation"
      header={
        <SideNavHeading
          icon={<Braces className="brand-mark" />}
          heading="Traceback"
          headingHref={`/${org.id}/overview`}
        />
      }
      topContent={
        <div className="org-select">
          <Selector
            label="Organization"
            options={orgs.map((o) => ({ value: o.id, label: o.name }))}
            value={org.id}
            width="100%"
            onChange={(id) => {
              clearReplays();
              router.push(`/${id}/overview`);
            }}
          />
        </div>
      }
      footer={
        <div className="nav-footer">
          <p className="small muted">
            {org.role.toLowerCase()} workspace access
          </p>
          <DropdownMenu
            button={{
              label: session.identity.username || "Your account",
              variant: "ghost",
              width: "100%",
            }}
            items={[
              { label: "Account settings", href: `/${org.id}/settings` },
              {
                label: "Sign out",
                icon: <LogOut size={16} />,
                onClick: () => {
                  void logout();
                },
              },
            ]}
          />
          <Button
            label={
              mode === "dark"
                ? "Dark appearance"
                : mode === "light"
                  ? "Light appearance"
                  : "System appearance"
            }
            icon={<SunMoon size={16} />}
            variant="ghost"
            width="100%"
            onClick={() => setMode(mode === "dark" ? "light" : "dark")}
          />
        </div>
      }
    >
      <SideNavSection title="Workspace">
        {destinations.map(([id, title, Icon]) => (
          <SideNavItem
            key={id}
            label={title}
            icon={<Icon size={18} />}
            href={`/${org.id}/${id}`}
            isSelected={(params.screen?.[0] ?? "overview") === id}
          />
        ))}
      </SideNavSection>
    </SideNav>
  );
  return (
    <AppShell
      height="fill"
      variant="section"
      sideNav={sideNav}
      mobileNav={{ breakpoint: "md" }}
    >
      <TenantBoundary
        key={`${session.identity.issuer}:${session.identity.subject}:${org.id}`}
        context={{ organization: org, session }}
      >
        {children}
      </TenantBoundary>
    </AppShell>
  );
}
