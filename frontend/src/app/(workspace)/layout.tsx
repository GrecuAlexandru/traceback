import { redirect } from "next/navigation";
import { getSession } from "@/lib/server/session";
import { Workspace } from "@/components/workspace";
export default async function WorkspaceLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  try {
    await getSession();
  } catch {
    redirect("/login?error=session_expired");
  }
  return <Workspace>{children}</Workspace>;
}
