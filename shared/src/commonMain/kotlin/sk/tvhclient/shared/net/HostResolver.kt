package sk.tvhclient.shared.net

/**
 * M581-fix: preklad mena servera na IP (uprednostni IPv4). Vracia null, ked sa
 * nepodari alebo platforma preklad neposkytuje. Blokujuce — volat mimo hlavneho vlakna.
 * Pouziva sa na zapamatanie poslednej funkcnej adresy: na mobilnej sieti DNS obcas
 * zlyha na dlhsie nez par sekund, ale server na starej IP bezi dalej.
 */
expect fun resolveHostBlocking(host: String): String?
