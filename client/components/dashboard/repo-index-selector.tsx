"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { FileText, FolderTree } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import { useRepoTree, useStartIndexing } from "@/hooks/use-repos";
import type { RepoTreeEntry, Repository } from "@/lib/api";

const MAX_SELECTED_FILES = 150;

export function RepoIndexSelector({
    repo,
    open,
    onOpenChange,
}: {
    repo: Repository;
    open: boolean;
    onOpenChange: (value: boolean) => void;
}) {
    const router = useRouter();
    const repoTreeQuery = useRepoTree(repo.id, open);
    const indexMutation = useStartIndexing();
    const [selectedPaths, setSelectedPaths] = useState<string[]>([]);

    const entries = repoTreeQuery.data ?? [];
    const eligibleEntries = useMemo(
        () => entries.filter((entry) => entry.eligible),
        [entries]
    );

    useEffect(() => {
        if (!repoTreeQuery.isSuccess) return;
        const initialSelection = eligibleEntries
            .slice(0, MAX_SELECTED_FILES)
            .map((entry) => entry.path);
        setSelectedPaths(initialSelection);
    }, [eligibleEntries, repoTreeQuery.isSuccess]);

    const tooLarge = eligibleEntries.length > MAX_SELECTED_FILES;
    const groupedEntries = useMemo(() => {
        const groups = new Map<string, RepoTreeEntry[]>();
        for (const entry of eligibleEntries) {
            const dir = entry.path.includes("/")
                ? entry.path.split("/").slice(0, -1).join("/")
                : "root";
            const current = groups.get(dir) ?? [];
            current.push(entry);
            groups.set(dir, current);
        }
        return [...groups.entries()].sort(([left], [right]) =>
            left.localeCompare(right)
        );
    }, [eligibleEntries]);

    const allSelected =
        eligibleEntries.length > 0 && selectedPaths.length === eligibleEntries.length;

    function getDirectoryPaths(directory: string) {
        const dirEntries = groupedEntries.find(([name]) => name === directory)?.[1] ?? [];
        return dirEntries.map((entry) => entry.path);
    }

    function isDirectorySelected(directory: string) {
        const paths = getDirectoryPaths(directory);
        return paths.length > 0 && paths.every((path) => selectedPaths.includes(path));
    }

    function togglePath(path: string) {
        setSelectedPaths((current) =>
            current.includes(path)
                ? current.filter((value) => value !== path)
                : [...current, path]
        );
    }

    function toggleDirectory(directory: string) {
        const paths = getDirectoryPaths(directory);
        if (paths.length === 0) return;

        const allSelectedNow = paths.every((path) => selectedPaths.includes(path));
        setSelectedPaths((current) => {
            const next = new Set(current);
            if (allSelectedNow) {
                paths.forEach((path) => next.delete(path));
            } else {
                paths.forEach((path) => next.add(path));
            }
            return [...next];
        });
    }

    function toggleAll() {
        const paths = eligibleEntries
            .slice(0, MAX_SELECTED_FILES)
            .map((entry) => entry.path);
        setSelectedPaths((current) =>
            current.length === paths.length && paths.every((path) => current.includes(path))
                ? []
                : paths
        );
    }

    function submit() {
        const selected = selectedPaths.slice();
        indexMutation.mutate(
            { repoId: repo.id, paths: selected },
            {
                onSuccess: () => {
                    onOpenChange(false);
                    router.push(`/chat/${repo.id}`);
                },
            }
        );
    }

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="max-w-2xl">
                <DialogHeader>
                    <DialogTitle>Choose files to index</DialogTitle>
                    <DialogDescription>
                        Select only the files you want added to the vector index for {repo.fullName}.
                    </DialogDescription>
                </DialogHeader>

                <div className="flex items-center justify-between gap-3 rounded-xl border border-dashed bg-muted/30 p-3 text-xs text-muted-foreground">
                    <span>{eligibleEntries.length} eligible files</span>
                    <Button variant="outline" size="sm" onClick={toggleAll} type="button">
                        {allSelected ? "Clear all" : "Select all"}
                    </Button>
                </div>

                {tooLarge && (
                    <div className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-3 text-xs text-amber-700 dark:text-amber-300">
                        This repo has more than {MAX_SELECTED_FILES} eligible files. Select up to {MAX_SELECTED_FILES} files before starting indexing.
                    </div>
                )}

                <div className="max-h-[420px] space-y-4 overflow-y-auto pr-1">
                    {repoTreeQuery.isLoading ? (
                        <p className="text-sm text-muted-foreground">Loading repo files…</p>
                    ) : groupedEntries.length === 0 ? (
                        <p className="text-sm text-muted-foreground">
                            No eligible files were found in this repo.
                        </p>
                    ) : (
                        groupedEntries.map(([directory, items]) => (
                            <div key={directory} className="space-y-2">
                                <div className="flex items-center justify-between gap-2 rounded-lg border border-dashed bg-muted/20 px-2 py-1.5">
                                    <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                                        <FolderTree className="size-3.5" />
                                        {directory === "root" ? "Root" : directory}
                                    </div>
                                    <label className="flex cursor-pointer items-center gap-2 text-xs text-muted-foreground">
                                        <Checkbox
                                            checked={isDirectorySelected(directory)}
                                            onCheckedChange={() => toggleDirectory(directory)}
                                        />
                                        <span>Select folder</span>
                                    </label>
                                </div>
                                <div className="space-y-2 pl-4">
                                    {items.map((item) => (
                                        <label
                                            key={item.path}
                                            className="flex cursor-pointer items-center gap-3 rounded-lg border border-dashed p-2 text-sm hover:bg-muted/30"
                                        >
                                            <Checkbox
                                                checked={selectedPaths.includes(item.path)}
                                                onCheckedChange={() => togglePath(item.path)}
                                            />
                                            <FileText className="size-4 text-muted-foreground" />
                                            <span className="min-w-0 flex-1 truncate">{item.path}</span>
                                            <span className="text-xs text-muted-foreground">
                                                {item.size != null ? `${Math.max(0, Math.round(item.size / 1024))} KB` : "-"}
                                            </span>
                                        </label>
                                    ))}
                                </div>
                            </div>
                        ))
                    )}
                </div>

                <DialogFooter>
                    <Button variant="outline" onClick={() => onOpenChange(false)} type="button">
                        Cancel
                    </Button>
                    <Button
                        onClick={submit}
                        disabled={
                            indexMutation.isPending ||
                            selectedPaths.length === 0 ||
                            selectedPaths.length > MAX_SELECTED_FILES
                        }
                        type="button"
                    >
                        {indexMutation.isPending ? "Starting…" : "Index selected files"}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
