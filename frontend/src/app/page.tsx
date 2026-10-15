import { redirect } from "next/navigation";
import { getSession } from "@/lib/server/session";
import { Bootstrap } from "@/components/bootstrap";
export default async function Home() {
  try {
    await getSession();
  } catch {
    redirect("/login");
  }
  return <Bootstrap />;
}
