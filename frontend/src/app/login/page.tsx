import { Button } from "@astryxdesign/core/Button";
import { Banner } from "@astryxdesign/core/Banner";
import { Braces, FileSearch, ShieldCheck } from "lucide-react";
export default async function Login({
  searchParams,
}: {
  searchParams: Promise<{ error?: string }>;
}) {
  const { error } = await searchParams;
  return (
    <main className="login-page">
      <section className="login-story">
        <a className="brand" href="/login">
          <Braces aria-hidden="true" />
          Traceback
        </a>
        <section>
          <h1>
            Find the thread.
            <br />
            Understand the incident.
          </h1>
          <p>
            Bring your incidents, engineering knowledge, and investigation
            history into one workspace.
          </p>
          <div className="login-capability">
            <FileSearch aria-hidden="true" />
            <span>Search your runbooks. Follow the evidence.</span>
          </div>
        </section>
        <p className="muted">Engineering knowledge, connected.</p>
      </section>
      <section className="login-form">
        <div className="login-box">
          <h2>Welcome to Traceback</h2>
          <p className="muted">Sign in to your engineering workspace.</p>
          {error ? (
            <Banner
              status="error"
              title={
                error === "session_expired"
                  ? "Your session has expired"
                  : "Sign-in could not be completed"
              }
              description="Sign in again. If the problem continues, check the local Keycloak and backend services."
            />
          ) : null}
          <Button
            label="Continue with Keycloak"
            variant="primary"
            href="/api/auth/login"
            as="a"
            width="100%"
          />
          <p className="login-security">
            <ShieldCheck aria-hidden="true" /> Secure sign-in through your
            identity provider
          </p>
          <p className="muted small">
            Your organization's membership controls access to incidents,
            documents, and conversations.
          </p>
        </div>
      </section>
    </main>
  );
}
