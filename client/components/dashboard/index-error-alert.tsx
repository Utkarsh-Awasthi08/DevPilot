"use client";

import { useState } from "react";
import { AlertCircle, AlertTriangle, ChevronDown } from "lucide-react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { cn } from "@/lib/utils";

const SUMMARY_LIMIT = 100;

function getErrorSummary(message: string) {
  const line = message.split("\n").find((part) => part.trim())?.trim() ?? message;
  if (line.length <= SUMMARY_LIMIT) return line;
  return `${line.slice(0, SUMMARY_LIMIT - 1)}…`;
}

export function IndexErrorAlert({
  message,
  title = "Indexing failed",
  variant = "error",
}: {
  message: string;
  title?: string;
  variant?: "error" | "warning";
}) {
  const [open, setOpen] = useState(false);
  const summary = getErrorSummary(message);
  const isExpandable = message.trim().length > summary.length;
  const isWarning = variant === "warning";

  return (
    <Alert
      variant={isWarning ? "default" : "destructive"}
      className={cn(
        "border-dashed px-3 py-2.5",
        isWarning
          ? "border-amber-500/30 bg-amber-500/5 text-amber-700 dark:text-amber-400"
          : "border-destructive/30 bg-destructive/5"
      )}
    >
      {isWarning ? <AlertTriangle className="size-4" /> : <AlertCircle className="size-4" />}
      <AlertTitle className="text-xs font-semibold">{title}</AlertTitle>
      <AlertDescription
        className={cn("text-xs", isWarning ? "text-amber-700/90 dark:text-amber-400/90" : "text-destructive/90")}
      >
        {!isExpandable ? (
          <p className="wrap-break-word leading-relaxed">{message}</p>
        ) : (
          <Collapsible open={open} onOpenChange={setOpen}>
            {!open && (
              <p className="wrap-break-word leading-relaxed">{summary}</p>
            )}
            <CollapsibleTrigger
              className={cn(
                "inline-flex items-center gap-1 font-medium hover:underline",
                isWarning ? "text-amber-700 dark:text-amber-400" : "text-destructive",
                !open ? "mt-1.5" : "mt-0"
              )}
            >
              {open ? "Hide details" : "Show details"}
              <ChevronDown
                className={cn(
                  "size-3 transition-transform",
                  open && "rotate-180"
                )}
              />
            </CollapsibleTrigger>
            <CollapsibleContent className="mt-2">
              <div
                className={cn(
                  "max-h-28 overflow-y-auto rounded-md border p-2.5",
                  isWarning
                    ? "border-amber-500/20 bg-amber-500/10"
                    : "border-destructive/20 bg-destructive/10"
                )}
              >
                <p className="wrap-break-word whitespace-pre-wrap leading-relaxed">
                  {message}
                </p>
              </div>
            </CollapsibleContent>
          </Collapsible>
        )}
      </AlertDescription>
    </Alert>
  );
}