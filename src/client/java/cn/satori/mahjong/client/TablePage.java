package cn.satori.mahjong.client;

/** Screen ownership keeps background table updates from navigating another table's UI. */
interface TablePage {
    ClientController controller();
}
