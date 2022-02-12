package graph_summarization;

import com.sun.org.apache.xerces.internal.impl.dtd.models.CMNode;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.hash.TIntHashSet;
import it.unimi.dsi.webgraph.ImmutableGraph;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.AreaAveragingScaleFilter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Summary {
    // webgraph 框架中的不变图对象，可以用来获取图的顶点和边属性
    ImmutableGraph Gr;
    // 图的邻居属性，因为有的图存在自环边，因此自行修改
    int[][] neighbors_;

    // 参数signature_length是指LSH算法中的k的大小
    int signature_length;
    // 参数max_group_size是指每个小组允许的最大顶点数量, 超过阈值的小组需要进行切割
    int max_group_size;

    // 图的顶点数量
    int n;
    // 超点数组，用来指明每个顶点的超点编号，如 S[3]=2 表示原图顶点3的超点编号是2
    int[] S;
    // 记录超点的第一个顶点，如 I[3]=5 表示超点编号3的第一个子顶点是5，而 I[4]=-1 则表示没有编号是4的超点
    int[] I;
    // 记录同属一个超点的下一个顶点是哪个，就像是链表的next指针，如 I[3]=9 表示和顶点3同处一个超点的下一个顶点是9
    int[] J;

    // 用于对顶点的重新编号
    int[] h;

    // 用于记录每个超点的大小
    int[] supernode_sizes;

    // 下面是用于encode superEdges的数据结构
    HashMap<Integer, TIntArrayList> sn_to_n;
    ArrayList<Pair<Integer, Integer>> P;
    TIntArrayList Cp_0, Cp_1;
    TIntArrayList Cm_0, Cm_1;

    Map<Integer, List<Integer>> P_neighbors;
    Map<Integer, List<Integer>> Cp_neighbors;
    Map<Integer, List<Integer>> Cm_neighbors;

    // 日志文件
    Logger logger_ = LoggerFactory.getLogger(getClass());
    /**
     * 构造函数，用于初始化一些共同的结构
     *
     * @param basename 数据集的基本名字
     */
    public Summary(String basename) throws Exception {
        // 调用 webgraph 框架来读取数据并构造图
        Gr = ImmutableGraph.loadMapped(basename);
        n = Gr.numNodes();
        signature_length = 0;
        max_group_size = 0;
        // 修正邻居集合
        neighbors_ = new int[n][];
        S = new int[n];
        I = new int[n];
        J = new int[n];

        logger_.info("调用初始化函数: 开始初始化数据结构，并纠正数据集中错误的顶点邻居");
        // 初始化每个顶点为一个超点，即分别设置 S[i]=i, I[i]=i 和 J[i]=i
        for (int i = 0; i < n; i++) {
            S[i] = i;  //Initial each node as a supernode
            I[i] = i;
            J[i] = -1;

            // 纠正顶点的邻居数据
            List<Integer> temp = new ArrayList<>();
            for (int node : Gr.successorArray(i)) {
                if(node == i) continue;
                temp.add(node);
            }
            neighbors_[i] = new int[temp.size()];
            for (int j = 0; j < temp.size(); j++) {
                neighbors_[i][j] = temp.get(j);
            }
        }
    }

    /**
     * 设置Local Sensitive Hash函数的参数k
     *
     * @param signatureLength 参数k
     */
    public void setSignature_length(int signatureLength){
        this.signature_length = signatureLength;
    }

    /**
     * 设置每个小组顶点最大数量的阈值
     *
     * @param max_group_size 阈值整数
     */
    public void setMax_group_size(int max_group_size){
        this.max_group_size = max_group_size;
    }

    /**
     * 更新超点，即合并两个超点，需要把第二个超点的所有顶点都合并到第一个超点里面(这里做了一个特殊处理，把编号大的合并到小的编号里面)
     *
     * @param super_node_a 需要合并的超点a的编号
     * @param super_node_b 需要合并的超点b的编号
     */
    protected void updateSuperNode(int super_node_a, int super_node_b) {
        int a = Math.min(super_node_a, super_node_b);
        int b = Math.max(super_node_a, super_node_b);
        int[] A_nodes = recoverSuperNode(a);
        int[] B_nodes = recoverSuperNode(b);
        J[A_nodes[A_nodes.length - 1]] = I[b];
        I[b] = -1;
        for (int a_node : A_nodes) S[a_node] = I[a];
        for (int b_node : B_nodes) S[b_node] = I[a];
    }

    /**
     * 计算超点包含多少个顶点
     *
     * @param super_node_id 超点编号
     * @return 返回超点包含顶点的数量
     */
    protected int superNodeLength(int super_node_id) {
        int counter = 0;
        int node = I[super_node_id];
        while (node != -1) {
            counter++;
            node = J[node];
        }
        return counter;
    }

    /**
     * 恢复超点，即计算超点包含哪些顶点
     *
     * @param super_node_id 超点的编号
     * @return 一个顶点数组
     */
    protected int[] recoverSuperNode(int super_node_id) {
        //Extracting the nodes belong to supernode key and return it (Arr)
        int length = superNodeLength(super_node_id);
        int[] nodes = new int[length];
        int counter = 0;
        int node = I[super_node_id];
        while (node != -1) {
            nodes[counter++] = node;
            node = J[node];
        }
        return nodes;
    }

    /**
     * 为一个小组Q内的所有超点建立HashMap<Integer, Integer>
     * 建立HashMap的目的是方便后续计算Jaccard Similarity 和 Saving
     * 一个超点的HashMap里面存储了 <u, num> 其中顶点 u 和该超点存在 num 条边相连
     *
     * @param Q          组内的所有超点编号
     * @param group_size 组的大小
     * @return 返回组内顶点所有的HashMap，用于后续计算顶点合并所需的saving
     */
    protected HashMap<Integer, HashMap<Integer, Integer>> createW(int[] Q, int group_size) {
        HashMap<Integer, HashMap<Integer, Integer>> w_All = new HashMap<>();
        for (int i = 0; i < group_size; i++) {
            HashMap<Integer, Integer> w_Single = new HashMap<Integer, Integer>();
            int[] Nodes = recoverSuperNode(Q[i]);
            for (int u : Nodes) {
                int[] Neigh = neighbors_[u];
//                int[] Neigh = Gr.successorArray(Nodes[j]);
                for (int v : Neigh) {
                    if (w_Single.containsKey(v))
                        w_Single.put(v, w_Single.get(v) + 1);
                    else
                        w_Single.put(v, 1);
                }
            }
            w_All.put(i, w_Single);
        }
        return w_All;
    }

    /**
     * 计算一个组内所有超点的w，每个顶点的w都是<node_id, num>，返回的是 <index, w> 即顶点在组内的index
     * @param Q 一个包含多个顶点的小组
     * @return 返回组内顶点所有的HashMap，用于后续计算顶点合并所需的saving
     */
    protected HashMap<Integer, HashMap<Integer, Integer>> createW(List<Integer> Q) {
        HashMap<Integer, HashMap<Integer, Integer>> w_All = new HashMap<>();
        int group_size = Q.size();
        for (int i = 0; i < group_size; i++) {
            w_All.put(i, createSingleW(Q.get(i)));
        }
        return w_All;
    }

    /**
     * 计算一个超点的w,具体是 <node_id, num>，即记录与超点存在边相连的顶点id以及边数量
     *
     * @param super_node_id 超点编号
     * @return 返回的是一个Map, 记录的是<node_id, num>
     */
    protected HashMap<Integer, Integer> createSingleW(int super_node_id) {
        HashMap<Integer, Integer> w_Single = new HashMap<>();
        int[] Nodes = recoverSuperNode(super_node_id);
        for (int u : Nodes) {
            int[] Neigh = neighbors_[u];
//            int[] Neigh = Gr.successorArray(node);
            for (int v : Neigh) {
                if (w_Single.containsKey(v))
                    w_Single.put(v, w_Single.get(v) + 1);
                else
                    w_Single.put(v, 1);
            }
        }
        return w_Single;
    }

    /**
     * 更新超点的HashMap
     * 当合并两个超点时，其中一个超点的所有顶点并入到另一个超点里面，这时需要更新两个超点的HashMap，保证后续计算的准确性
     *
     * @param w_A 超点A的HashMap
     * @param w_B 超点B的HashMap
     * @return 更新超点A和B合并后的HashMap
     */
    protected HashMap<Integer, Integer> updateW(HashMap<Integer, Integer> w_A, HashMap<Integer, Integer> w_B) {
        HashMap<Integer, Integer> result = new HashMap<>();
        for (Integer key : w_A.keySet()) {
            if (w_B.containsKey(key))
                result.put(key, w_A.get(key) + w_B.get(key));
            else
                result.put(key, w_A.get(key));
        }
        for (Integer key : w_B.keySet()) {
            if (w_A.containsKey(key))
                continue;
            result.put(key, w_B.get(key));
        }
        return result;
    }

    /**
     * 计算两个超点之间的Jaccard Similarity
     *
     * @param w_A 超点A的HashMap
     * @param w_B 超点B的HashMap
     * @return 返回double数值
     */
    protected double computeJacSim(HashMap<Integer, Integer> w_A, HashMap<Integer, Integer> w_B) {
        int down = 0;
        int up = 0;
        for (Integer key : w_A.keySet()) {
            if (w_B.containsKey(key)) {
                if (w_A.get(key) <= w_B.get(key)) {
                    up = up + w_A.get(key);
                    down = down + w_B.get(key);
                } else {
                    down = down + w_A.get(key);
                    up = up + w_B.get(key);
                }
            } else {
                down = down + w_A.get(key);
            }
        }
        for (Integer key : w_B.keySet()) {
            if (!(w_A.containsKey(key))) {
                down = down + w_B.get(key);
            }
        }
        return (up * 1.0) / (down * 1.0);
    }

    /**
     * 计算两个超点之间的Saving，即合并能带来的收益
     *
     * @param w_A         超点A的HashMap
     * @param w_B         超点B的HashMap
     * @param supernode_A 超点A的编号
     * @param supernode_B 超点B的编号
     * @return 返回double数值
     */
    protected double computeSaving(HashMap<Integer, Integer> w_A, HashMap<Integer, Integer> w_B, int supernode_A, int supernode_B) {
        int num_A = recoverSuperNode(supernode_A).length;
        int num_B = recoverSuperNode(supernode_B).length;
        double cost_A = 0, cost_B = 0, cost_AUnionB = 0;
        // 这个HashMap用于存储与合并后的超点存在边相连的超点大小
        HashMap<Integer, Integer> candidate_size = new HashMap<Integer, Integer>();
        // 这个HashMap用于存储与超点A存在边相连的所有边数量
        HashMap<Integer, Integer> candidate_spA = new HashMap<Integer, Integer>();
        // 这个HashMap用于存储与超点B存在边相连的所有边数量
        HashMap<Integer, Integer> candidate_spB = new HashMap<Integer, Integer>();

        // 遍历w_A得到与超点A存在边相连的顶点u以及边数量num
        for (Integer key : w_A.keySet()) {
            if (!candidate_size.containsKey(S[key])) {
                int[] nodes = recoverSuperNode(S[key]);
                candidate_size.put(S[key], nodes.length);
                candidate_spA.put(S[key], w_A.get(key));
            } else {
                candidate_spA.put(S[key], candidate_spA.get(S[key]) + w_A.get(key));
            }
        }
        if(candidate_spA.containsKey(supernode_A)) candidate_spA.put(supernode_A, candidate_spA.get(supernode_A) / 2);

        // 遍历w_B得到与超点B存在边相连的顶点u以及边数量num
        for (Integer key : w_B.keySet()) {
            if (!candidate_size.containsKey(S[key])) {
                int[] nodes = recoverSuperNode(S[key]);
                candidate_size.put(S[key], nodes.length);
                candidate_spB.put(S[key], w_B.get(key));
            } else if (candidate_spB.containsKey(S[key])) {
                candidate_spB.put(S[key], candidate_spB.get(S[key]) + w_B.get(key));
            } else {
                candidate_spB.put(S[key], w_B.get(key));
            }
        }
        if(candidate_spB.containsKey(supernode_B)) candidate_spB.put(supernode_B, candidate_spB.get(supernode_B) / 2);

        // 开始计算超点A，B以及合并后超点的代价 cost_A, cost_B 和 cost_AUnionB
        for (Integer key : candidate_spA.keySet()) {
            int E = candidate_spA.get(key);
            double compare = key == supernode_A ? ((num_A * 1.0 * (num_A - 1)) / 2.0) : (num_A * 1.0 * candidate_size.get(key));
            cost_A += (E <= compare / 2.0) ? (E) : (1 + compare - E);

            if (key == supernode_B || key == supernode_A)
                continue;

            if(candidate_spB.containsKey(key))
                E += candidate_spB.get(key);
            compare = key == supernode_A ? (((num_A + num_B) * 1.0 * (num_A + num_B - 1)) / 2.0) : ((num_A + num_B) * 1.0 * candidate_size.get(key));
            cost_AUnionB += (E <= compare / 2.0) ? (E) : (1 + compare - E);
        }
        for (Integer key : candidate_spB.keySet()) {
            int E = candidate_spB.get(key);
            double compare = key == supernode_B ? ((num_B * 1.0 * (num_B - 1)) / 2.0) : (num_B * 1.0 * candidate_size.get(key));
            cost_B += (E <= compare / 2.0) ? (E) : (1 + compare - E);

            if (key == supernode_B || key == supernode_A)
                continue;

            if (!candidate_spA.containsKey(key)) {
                compare = key == supernode_B ? (((num_A + num_B) * 1.0 * (num_A + num_B - 1)) / 2.0) : ((num_A + num_B) * 1.0 * candidate_size.get(key));
                cost_AUnionB += (E <= compare / 2.0) ? (E) : (1 + compare - E);
            }
        }

        int E = 0;
        // 超点A存在自环边
        if (candidate_spA.containsKey(supernode_A))
            E += candidate_spA.get(supernode_A);
        // 超点A和B存在超边
        if (candidate_spA.containsKey(supernode_B))
            E += candidate_spA.get(supernode_B);
        // 超点B存在自环边
        if (candidate_spB.containsKey(supernode_B))
            E += candidate_spB.get(supernode_B);
        if (E > 0) {
            double compare = ((num_A + num_B) * 1.0 * (num_A + num_B - 1)) / 2.0;
            cost_AUnionB += (E <= compare / 2.0) ? (E) : (1 + compare - E);
        }
        return 1 - (cost_AUnionB) / (cost_A + cost_B);
    }

    /**
     * 对顶点的一次重新编号 h: |V| -> |V|
     */
    protected void randomPermutation(){
        h = new int[n];
        for (int i = 0; i < n; i++) {
            h[i] = i;
        }
        Random rnd = new Random();
        rnd = ThreadLocalRandom.current();
        for (int i = h.length - 1; i > 0; i--) {
            int index = rnd.nextInt(i + 1);
            int a = h[index];
            h[index] = h[i];
            h[i] = a;
        }
    }

    /**
     * 传入参数k和随机生成的数组D, 为超点A计算其LSH签名, 使用的是单枚举算法
     * @param A 超点A的编号
     * @param k LSH签名的长度
     * @param rot_direction 随机生成的数组D
     * @return 返回超点A的LSH签名对象，为OnePermHashSig
     */
    protected OnePermHashSig generateSignature(int A, int k, int[] rot_direction) {
        // 通过参数k,确定签名的块数k_bins 和 每一块的长度 bin_size
        int k_bins = k;
        int bin_size = n / k_bins;
        if (n % k_bins != 0) { k_bins = k_bins + 1; }
        OnePermHashSig hashSig = new OnePermHashSig(k_bins);

        // A不是一个超点
        if (I[A] == -1) return hashSig;
        // 遍历超点的每个顶点v
        for (int v = I[A]; ; v=J[v]) {
            int[] neighbours = neighbors_[v];
//            int[] neighbours = Gr.successorArray(v);
            // 遍历顶点v的每个邻居j
            for (int neighbour : neighbours) {
                // 得到邻居j重排后的id
                int permuted_h = h[neighbour];
                // 以及在第几个bin
                int permuted_bin = permuted_h / bin_size;
                // 如果b_i==-1(即还没设置)或者比b_i还小的索引 (permuted_h%bin_size) 存在顶点，则重新设置b_i
                if (hashSig.sig[permuted_bin] == -1 || permuted_h % bin_size < hashSig.sig[permuted_bin]) {
                    hashSig.sig[permuted_bin] = permuted_h % bin_size;
                }
            }
            // 遍历完超点A的所有顶点就直接退出
            if(J[v]==-1) break;
        }

        // 开始处理块b_i是empty的情况，即rotation
        for (int A_bin = 0; A_bin < k_bins; A_bin++) {
            int direction = rot_direction[A_bin];
            // 如果b_i还没设置, 则利用rot_direction来确定往哪边采样
            if (hashSig.sig[A_bin] == -1) {
                int i = (A_bin + direction) % k_bins;
                if (i < 0) { i += k_bins; }
                int counter = 0;
                while (hashSig.sig[i] == -1 && counter < k_bins) {
                    i = (i + direction) % k_bins;
                    if (i < 0) { i += k_bins; }
                    counter++;
                }
                hashSig.sig[A_bin] = hashSig.sig[i];
            }
        }

        return hashSig;
    }

    /**
     * 为超点计算shingle签名
     * 每个超点A的shingle值等于其所有顶点shingle值的最小值, 而每个顶点shingle值等于其邻域内所有顶点的最小重排编号
     *
     * @param A 超点A的编号
     * @return 一个整数值
     */
    protected int generateSignature(int A){
        // 将超点A的shingle值初始化成最大值
        int shingle = n;
        // A不是一个超点
        if (I[A] == -1) return shingle;
        // 逐个计算超点A所包含顶点的shingle值, 并确定超点A的shingle值
        for (int u = I[A]; ; u = J[u]) {
            // 当前计算的是顶点u的shingle值
            int f_u = h[u];
            for(int v : neighbors_[u]){
                if(f_u > h[v]) f_u = h[v];
            }
            if (shingle > f_u)
                shingle = f_u;
            if (J[u] == -1)
                break;
        }
        return shingle;
    }

    /**
     * 顶点初始化的阶段，Greedy算法需要进行重载
     */
    public double initialPhase(double threshold) {
        return 0.0;
    }

    /**
     * 顶点分组的阶段，SWeG和LDME算法需要进行重载
     */
    public double dividePhase() {
        return 0.0;
    }

    /**
     * 顺序切割顶点数量大于max_group_size的小组
     * @param group 顶点小组
     * @param max_group_size 按max_group_size大小进行顺序切割小组
     * @return 返回切割后的每个小组, 小组的最大顶点数量为max_group_size
     */
    public List<List<Integer>> splitGroup(List<Integer> group, int max_group_size) {
        List<List<Integer>> groups = new ArrayList<>();
        int group_size = group.size();
        int num = group_size / max_group_size;
        if(group_size % max_group_size != 0) num += 1;
        for (int j = 0; j < num; j++) {
            int start = j * max_group_size;
            int end = Math.min((j + 1) * max_group_size, group_size);
            List<Integer> subGroup = new ArrayList<>();
            for (int k = start; k < end; k++) {
                subGroup.add(group.get(k));
            }
            groups.add(subGroup);
        }
        return groups;
    }

    /**
     * 默认分组, 采用的是shingle方式, 如果小组顶点数量大于max_group_size, 则采用顺序切割
     * @param max_group_size 小组的最大顶点数量
     * @return 一个List<List<>>, 里面的List存放了每个分组的顶点编号
     */
    public List<List<Integer>> divide(int max_group_size){
        int num = n;
        List<List<Integer>> groups = new ArrayList<>();
        if (max_group_size > 0) {
            logger_.info(String.format("开始分组, 采用的是Shingle方式, 对%d个顶点进行分组, 最大的小组顶点数量为%d", num, max_group_size));
        } else {
            logger_.info(String.format("开始分组, 采用的是Shingle方式, 对%d个顶点进行分组, 不限制顶点数量", num));
        }
        long divideStartTime = System.currentTimeMillis();
        randomPermutation();
        int[] F_temp = new int[num];
        for(int A = 0; A < num; A++){
            F_temp[A] = generateSignature(A);
        }

        Integer[] G_temp = new Integer[num];
        for(int i = 0; i < num; i++) G_temp[i] = i;
        Arrays.sort(G_temp, (o1, o2) -> Integer.compare(F_temp[o1], F_temp[o2]));

        int g_start_temp = 0;
        while(F_temp[G_temp[g_start_temp]] == -1)
            g_start_temp++;
        int g = -1;
        ArrayList<Integer> Q = new ArrayList<>();
        for (int i = g_start_temp; i < num; i++) {
            if (F_temp[G_temp[i]] != g) {
                if (Q.size() > 1) {
                    if(max_group_size <= 0 || Q.size() <= max_group_size){
                        groups.add(Q);
                    } else {
                        List<List<Integer>> subGroups = splitGroup(Q, max_group_size);
                        for (List<Integer> subGroup : subGroups) {
                            if(subGroup.size() > 1) groups.add(subGroup);
                        }
                    }
                }
                g = F_temp[G_temp[i]];
                Q = new ArrayList<>();
            }
            Q.add(G_temp[i]);
        }
        if(Q.size() > 1){
            if(max_group_size <= 0 || Q.size() <= max_group_size) {
                groups.add(Q);
            } else {
                List<List<Integer>> subGroups = splitGroup(Q, max_group_size);
                for (List<Integer> subGroup : subGroups) {
                    if(subGroup.size() > 1) groups.add(subGroup);
                }
            }
        }
        logger_.info(String.format("分组结束, 花费时间 %5f seconds", (System.currentTimeMillis() - divideStartTime) / 1000.0));
        return groups;
    }

    /**
     * 新的分组方式, 采用的是Local Sensitive Hash方式, 如果小组顶点数量大于max_group_size， 则采用顺序切割
     * @param max_group_size 小组的最大顶点数量
     * @param signature_length 签名长度
     * @return 一个List<List<>>, 里面的List存放了每个分组的顶点编号
     */
    public List<List<Integer>> divide(int max_group_size, int signature_length){
        int num = n;
        List<List<Integer>> groups = new ArrayList<>();
        if (max_group_size > 0) {
            logger_.info(String.format("开始分组, 采用的是Local Sensitive Hash方式, 对%d个顶点进行分组, 最大的小组顶点数量为%d", num, max_group_size));
        } else {
            logger_.info(String.format("开始分组, 采用的是Local Sensitive Hash方式, 对%d个顶点进行分组, 不限制顶点数量", num));
        }
        long divideStartTime = System.currentTimeMillis();
        int k_bins = signature_length;
        if (n % k_bins != 0) {
            k_bins = k_bins + 1;
        }
        // 首先生成长度为k_bins的一个数组用于辅助计算hash签名值
        int[] rot_direction = new int[k_bins];
        Random random = new Random();
        for (int i = 0; i < k_bins; i++) {
            if (random.nextBoolean()) {
                rot_direction[i] = 1;
            } else {
                rot_direction[i] = -1;
            }
        }
        randomPermutation();
        OnePermHashSig[] F_temp = new OnePermHashSig[num];
        for (int A = 0; A < num; A++) {
            F_temp[A] = generateSignature(signature_length, A, rot_direction);
        }
        Integer[] G_temp = new Integer[num];
        for (int i = 0; i < num; i++) G_temp[i] = i;
        Arrays.sort(G_temp, (o1, o2) -> OnePermHashSig.compare(F_temp[o1], F_temp[o2]));
        int g_start_temp = 0;
        while (F_temp[G_temp[g_start_temp]].unassigned())
            g_start_temp++;
        OnePermHashSig g = new OnePermHashSig(k_bins);
        ArrayList<Integer> Q = new ArrayList<>();
        for (int i = g_start_temp; i < num; i++) {
            if (F_temp[G_temp[i]] != g) {
                if (Q.size() > 1) {
                    if(max_group_size <= 0 || Q.size() <= max_group_size){
                        groups.add(Q);
                    } else {
                        List<List<Integer>> subGroups = splitGroup(Q, max_group_size);
                        for (List<Integer> subGroup : subGroups) {
                            if(subGroup.size() > 1) groups.add(subGroup);
                        }
                    }
                }
                g = F_temp[G_temp[i]];
                Q = new ArrayList<>();
            }
            Q.add(G_temp[i]);
        }
        if(Q.size() > 1){
            if(max_group_size <= 0 || Q.size() <= max_group_size) {
                groups.add(Q);
            } else {
                List<List<Integer>> subGroups = splitGroup(Q, max_group_size);
                for (List<Integer> subGroup : subGroups) {
                    if(subGroup.size() > 1) groups.add(subGroup);
                }
            }
        }
        logger_.info(String.format("分组结束, 花费时间 %5f seconds", (System.currentTimeMillis() - divideStartTime) / 1000.0));
        return groups;
    }

    /**
     * 对所有分组都进行合并
     * @param groups 分组情况
     * @param threshold 合并阈值
     */
    public void merge(List<List<Integer>> groups, double threshold){
        logger_.info(String.format("开始合并, 当前顶点的合并阈值:%5f", threshold));
        long mergeStartTime = System.currentTimeMillis();
        for(List<Integer> group : groups){
            HashMap<Integer, HashMap<Integer, Integer>> hm = createW(group);
            int initial_size = hm.size();
            while (hm.size() > 1) {
                Random rand = new Random();
                // 从组内随机找到一个超点A
                int A = rand.nextInt(initial_size);
                if (hm.get(A) == null) continue;
                // 变量max记录最大的Jaccard_similarity
                double max = 0;
                // 变量idx记录最大Jaccard_similarity的那个顶点
                int idx = -1;
                // 遍历组内其他顶点，找到与A的Jaccard Similarity最大的那个顶点
                for (int j = 0; j < initial_size; j++) {
                    if (hm.get(j) == null)
                        continue;
                    if (j == A) continue;
                    double jaccard_similarity = computeJacSim(hm.get(A), hm.get(j));
                    if (jaccard_similarity > max) {
                        max = jaccard_similarity;
                        idx = j;
                    }
                }
                if (idx == -1) {
                    hm.remove(A);
                    continue;
                }
                // 这里做了一个交换，目的是把编号较大的顶点合并到编号较小的顶点里面
                if (group.get(A) > group.get(idx)) {
                    int t = A;
                    A = idx;
                    idx = t;
                }
                // 计算两个顶点之间的合并收益
                double savings = computeSaving(hm.get(A), hm.get(idx), group.get(A), group.get(idx));
                if (savings >= threshold) {
                    HashMap<Integer, Integer> w_update = updateW(hm.get(A), hm.get(idx));
                    hm.replace(A, w_update);
                    hm.remove(idx);
                    updateSuperNode(group.get(A), group.get(idx));
                } else {
                    hm.remove(A);
                }

            }
        }
        logger_.info(String.format("合并结束, 花费时间 %5f seconds", (System.currentTimeMillis() - mergeStartTime) / 1000.0));
    }

    /**
     * 顶点合并的阶段，SWeG和LDME算法需要进行重载
     *
     * @param threshold 合并阶段的阈值，低于阈值的顶点对不合并
     */
    public double mergePhase(double threshold) {
        return 0.0;
    }

    /**
     * 编码阶段：
     * (1)先对超点进行编码，即判断有多少个超点，并且记录每个超点对应哪些顶点集合
     * (2)接着对超边进行编码
     */
    public double encodePhase() {
        System.out.println("# Encode Phase");
        long startTime = System.currentTimeMillis();
        supernode_sizes = new int[n];
        sn_to_n = new HashMap<>();
        P = new ArrayList<>();
        Cp_0 = new TIntArrayList();
        Cp_1 = new TIntArrayList();
        Cm_0 = new TIntArrayList();
        Cm_1 = new TIntArrayList();
        int supernode_count = 0;
        int[] S_copy = Arrays.copyOf(S, S.length);

        // 对超点进行编码
        for (int i = 0; i < n; i++) {
            // 如果存在编号为i的超点
            if (I[i] != -1) {
                // 获取超点i包含的所有顶点
                int[] nodes_inside = recoverSuperNode(i);
                TIntArrayList nodes_inside_list = new TIntArrayList();
                supernode_sizes[supernode_count] = nodes_inside.length;
                for (int k : nodes_inside) {
                    nodes_inside_list.add(k);
                    // Rename the superNode from S_copy[nodes_inside[j]] to supernode_count
                    S_copy[k] = supernode_count;
                }
                sn_to_n.put(supernode_count, nodes_inside_list);    // sn_to_n record the relation between (superNode, nodes_list)
                supernode_count++;
            }
        }

        // Encode superEdges
        for (int A = 0; A < supernode_count; A++) {
            // get all nodes in superNode A
            TIntArrayList in_A = sn_to_n.get(A);
            // edges_count[B] means the num of edges between superNode A and B
            int[] edges_count = new int[supernode_count];
            // edges_list[B] is the sets of edges between superNode A and B
            HashSet<?>[] edges_list = new HashSet<?>[supernode_count];
            // record the superNode which has one edge with A at least
            TIntHashSet has_edge_with_A = new TIntHashSet();

            // find each edges between superNode A and other superNodes
            for (int a = 0; a < in_A.size(); a++) {
                int node = in_A.get(a);
                int[] neighbours = neighbors_[node];
//                int[] neighbours = Gr.successorArray(node);

                for (int neighbour : neighbours) {
                    // B = S_copy[neighbours[i]]
                    edges_count[S_copy[neighbour]]++;
                    // if this B has not already been processed
                    if (S_copy[neighbour] >= A) {
                        has_edge_with_A.add(S_copy[neighbour]);
                    }
                    // record the edge<node, neighbours[i]> in the edges_list[B]
                    if (edges_list[S_copy[neighbour]] == null) {
                        edges_list[S_copy[neighbour]] = new HashSet<Pair<Integer, Integer>>();
                    }
                    ((HashSet<Pair<Integer, Integer>>) edges_list[S_copy[neighbour]]).add(new Pair(node, neighbour));
                } // for i
            } // for A

            // process each superNode pair <A, B> at least one edge between them
            TIntIterator iter = has_edge_with_A.iterator();
            while (iter.hasNext()) {
                int B = (Integer) iter.next();
                double edge_compare_cond = 0;
                // figure out which situation: 1. A and A  2. A and B (differ from A)
                if (A == B) {
                    edge_compare_cond = supernode_sizes[A] * (supernode_sizes[A] - 1) * 1.0/ 4;
                } else {
                    edge_compare_cond = (supernode_sizes[A] * supernode_sizes[B]) * 1.0 / 2;
                }
                // do not add superEdge between superNode A and B
                if (edges_count[B] <= edge_compare_cond) {
                    // Add all edges between A and B to C+
                    for (Pair<Integer, Integer> edge : ((HashSet<Pair<Integer, Integer>>) edges_list[B])) {
                        // Cp_0 store the source node of edge, Cp_1 store the target node of edge
                        Cp_0.add(edge.getValue0());
                        Cp_1.add(edge.getValue1());
                    }

                } else { // add a superEdge between A and B to P and add the difference to C-
                    P.add(new Pair(A, B));
                    // get all nodes in superNode B
                    TIntArrayList in_B = sn_to_n.get(B);
                    // process each possible pair <a,b> where a in superNode A and b in superNode B
                    for (int a = 0; a < in_A.size(); a++) {
                        for (int b = 0; b < in_B.size(); b++) {
                            Pair<Integer, Integer> edge = new Pair(in_A.get(a), in_B.get(b));
                            // edge<a,b> do not exist truly, but we need to store it to the C-
                            if (!edges_list[B].contains(edge)) {
                                // Cm_0 store the source node of edge, Cm_1 store the target node of edge
                                Cm_0.add(in_A.get(a));
                                Cm_1.add(in_B.get(b));
                            }
                        } // for b
                    } // for a
                } // else
            } // for B
        } // for A

        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    /**
     * 编码阶段，在LDME算法中同样改进了这个地方，比上面的方法速度更快：
     * (1)先对超点进行编码，即判断有多少个超点，并且记录每个超点对应哪些顶点集合
     * (2)接着对超边进行编码
     */
    public double encodePhase_new(){
//        System.out.println("# Encode Phase");
        long startTime = System.currentTimeMillis();
        supernode_sizes = new int[n];
        sn_to_n = new HashMap<>();
        P = new ArrayList<>();
        Cp_0 = new TIntArrayList();
        Cp_1 = new TIntArrayList();
        Cm_0 = new TIntArrayList();
        Cm_1 = new TIntArrayList();
        int edges_compressed = 0;
        int supernode_count = 0;
        int[] S_copy = Arrays.copyOf(S, S.length);

        for (int i = 0; i < n; i++) {
            if (I[i] != -1) {
                int[] nodes_inside = recoverSuperNode(i);
                TIntArrayList nodes_inside_list = new TIntArrayList();
                supernode_sizes[supernode_count] = nodes_inside.length;
                for (int j = 0; j < nodes_inside.length; j++) {
                    nodes_inside_list.add(nodes_inside[j]);
                    S_copy[nodes_inside[j]] = supernode_count;
                }
                sn_to_n.put(supernode_count, nodes_inside_list);
                supernode_count++;
            }
        }

        LinkedList<FourTuple> edges_encoding = new LinkedList<FourTuple>();
        for (int node = 0; node < n; node++) {
            for(int neighbour : neighbors_[node]) {
//            for(int neighbour : Gr.successorArray(node)) {
                if (S_copy[node] <= S_copy[neighbour]) {
                    edges_encoding.add(new FourTuple(S_copy[node], S_copy[neighbour], node, neighbour));
                }
            }
        }
        Collections.sort(edges_encoding);

        int prev_A = edges_encoding.get(0).A;
        int prev_B = edges_encoding.get(0).B;
        HashSet<Pair<Integer, Integer>> edges_set = new HashSet<Pair<Integer, Integer>>();
        Iterator<FourTuple> iter = edges_encoding.iterator();
        while (!edges_encoding.isEmpty()) {
            FourTuple e_encoding = edges_encoding.pop();
            int A = e_encoding.A;
            int B = e_encoding.B;

            // 移动到新的顶点对，即已经得到前一个顶点对<prev_A, prev_B>的所有边信息，可以开始encode顶点对 <prev_A, prev_B>的超边信息
            if ((A != prev_A || B != prev_B)) { // we've moved onto a different pair of supernodes A and B

                if (prev_A <= prev_B) {
                    double edges_compare_cond = 0;
                    if (prev_A == prev_B) { edges_compare_cond = supernode_sizes[prev_A] * (supernode_sizes[prev_A] - 1) / 4.0; }
                    else                  { edges_compare_cond = (supernode_sizes[prev_A] * supernode_sizes[prev_B]) / 2.0;     }

                    // 不形成超边
                    if (edges_set.size() <= edges_compare_cond) {
//                        if (prev_A != prev_B) edges_compressed += edges_set.size();
                        // 每条边加入到C+集合
                        for (Pair<Integer, Integer> edge : edges_set) {
                            Cp_0.add(edge.getValue0());
                            Cp_1.add(edge.getValue1());
                        }
                    }
                    // 行成超边
                    else {
//                        if (prev_A != prev_B) edges_compressed += supernode_sizes[prev_A] * supernode_sizes[prev_B] - edges_set.size() + 1;

                        // 加入超边 <prev_A, prev_B>
                        P.add(new Pair(prev_A, prev_B));

                        TIntArrayList in_A = sn_to_n.get(prev_A);
                        TIntArrayList in_B = sn_to_n.get(prev_B);
                        for (int a = 0; a < in_A.size(); a++) {
                            for (int b = 0; b < in_B.size(); b++) {
                                Pair<Integer, Integer> edge = new Pair(in_A.get(a), in_B.get(b));
                                // 每条边加入到C-集合
                                if (!(edges_set.contains(edge))) {
                                    Cm_0.add(in_A.get(a));
                                    Cm_1.add(in_B.get(b));
                                }
                            } // for b
                        } // for a
                    } // else
                } // if

                edges_set = new HashSet<Pair<Integer, Integer>>();
            } // if

            edges_set.add(new Pair(e_encoding.u, e_encoding.v));
            prev_A = A;
            prev_B = B;
        } // for edges encoding
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    /**
     * 新的编码函数，与之前不同在于对自环进行了修改
     *
     * @return 运行时间
     */
    public double encodePhase_new2(){
//        System.out.println("# Encode Phase");
        long startTime = System.currentTimeMillis();
        supernode_sizes = new int[n];
        sn_to_n = new HashMap<>();
        P = new ArrayList<>();
        Cp_0 = new TIntArrayList();
        Cp_1 = new TIntArrayList();
        Cm_0 = new TIntArrayList();
        Cm_1 = new TIntArrayList();
        int edges_compressed = 0;
        int supernode_count = 0;
        int[] S_copy = Arrays.copyOf(S, S.length);

        // 对顶点进行重新编号
//        for (int i = 0; i < n; i++) {
//            if (I[i] != -1) {
//                int[] nodes_inside = recoverSuperNode(i);
//                TIntArrayList nodes_inside_list = new TIntArrayList();
//                supernode_sizes[supernode_count] = nodes_inside.length;
//                for (int j = 0; j < nodes_inside.length; j++) {
//                    nodes_inside_list.add(nodes_inside[j]);
//                    S_copy[nodes_inside[j]] = supernode_count;
//                }
//                sn_to_n.put(supernode_count, nodes_inside_list);
//                supernode_count++;
//            }
//        }

        // 把每条边按他们的所属的超边组合起来
        LinkedList<FourTuple> edges_encoding = new LinkedList<FourTuple>();
        for (int node = 0; node < n; node++) {
            for(int neighbour : neighbors_[node]) {
//            for(int neighbour : Gr.successorArray(node)) {
                if(node <= neighbour){
                    if (S[node] <= S[neighbour]) {
//                        edges_encoding.add(new FourTuple(S_copy[node], S_copy[neighbour], node, neighbour));
                        edges_encoding.add(new FourTuple(S[node], S[neighbour], node, neighbour));
                    }else{
//                        edges_encoding.add(new FourTuple(S_copy[neighbour], S_copy[node], neighbour, node));
                        edges_encoding.add(new FourTuple(S[neighbour], S[node], neighbour, node));
                    }
                }
            }
        }
        Collections.sort(edges_encoding);

        int prev_A = edges_encoding.get(0).A;
        int prev_B = edges_encoding.get(0).B;
        HashSet<Pair<Integer, Integer>> edges_set = new HashSet<Pair<Integer, Integer>>();
        Iterator<FourTuple> iter = edges_encoding.iterator();
        while (!edges_encoding.isEmpty()) {
            FourTuple e_encoding = edges_encoding.pop();
            int A = e_encoding.A;
            int B = e_encoding.B;

            // 移动到新的顶点对，即已经得到前一个顶点对<prev_A, prev_B>的所有边信息，可以开始encode顶点对 <prev_A, prev_B>的超边信息
            if ((A != prev_A || B != prev_B)) { // we've moved onto a different pair of supernodes A and B
                if (prev_A <= prev_B) {
                    double edges_compare_cond = 0;
                    if (prev_A == prev_B) {
                        edges_compare_cond = (superNodeLength(prev_A) * (superNodeLength(prev_A) - 1)) / 4.0;
//                        edges_compare_cond = supernode_sizes[prev_A] * (supernode_sizes[prev_A] - 1) / 4.0;
                    } else {
                        edges_compare_cond = (superNodeLength(prev_A) * superNodeLength(prev_B)) / 2.0;
//                        edges_compare_cond = (supernode_sizes[prev_A] * supernode_sizes[prev_B]) / 2.0;
                    }

                    // 不形成超边
                    if (edges_set.size() <= edges_compare_cond) {
//                        if (prev_A != prev_B) edges_compressed += edges_set.size();
                        // 每条边加入到C+集合
                        for (Pair<Integer, Integer> edge : edges_set) {
                            if (edge.getValue0() <= edge.getValue1()) {
                                Cp_0.add(edge.getValue0());
                                Cp_1.add(edge.getValue1());
                            }else{
                                Cp_0.add(edge.getValue1());
                                Cp_1.add(edge.getValue0());
                            }
                        }
                    }
                    // 行成超边
                    else {
//                        if (prev_A != prev_B) edges_compressed += supernode_sizes[prev_A] * supernode_sizes[prev_B] - edges_set.size() + 1;

                        // 加入超边 <prev_A, prev_B>
                        P.add(new Pair(prev_A, prev_B));

                        if (prev_A == prev_B) {
                            int[] in_A = recoverSuperNode(prev_A);
                            for (int a = 0; a < in_A.length; a++) {
                                for (int b = a + 1; b < in_A.length; b++) {
                                    Pair<Integer, Integer> edge_1 = new Pair<>(in_A[a], in_A[b]);
                                    Pair<Integer, Integer> edge_2 = new Pair<>(in_A[b], in_A[a]);
                                    if (!edges_set.contains(edge_1) && !edges_set.contains(edge_2)) {
                                        if (in_A[a] <= in_A[b]) {
                                            Cm_0.add(in_A[a]);
                                            Cm_1.add(in_A[b]);
                                        } else {
                                            Cm_0.add(in_A[b]);
                                            Cm_1.add(in_A[a]);
                                        }
                                    }
                                }
                            }
                        } else {
                            int[] in_A = recoverSuperNode(prev_A);
                            int[] in_B = recoverSuperNode(prev_B);
                            for (int a = 0; a < in_A.length; a++) {
                                for (int b = 0; b < in_B.length; b++) {
                                    Pair<Integer, Integer> edge_1 = new Pair<>(in_A[a], in_B[b]);
                                    Pair<Integer, Integer> edge_2 = new Pair<>(in_B[b], in_A[a]);
                                    if (!edges_set.contains(edge_1) && !edges_set.contains(edge_2)) {
                                        if (in_A[a] <= in_B[b]) {
                                            Cm_0.add(in_A[a]);
                                            Cm_1.add(in_B[b]);
                                        } else {
                                            Cm_0.add(in_B[b]);
                                            Cm_1.add(in_A[a]);
                                        }
                                    }  // if
                                }  // for b
                            } // for a
                        } // else
                    } // else
                } // if
                edges_set = new HashSet<Pair<Integer, Integer>>();
            } // if
            edges_set.add(new Pair(e_encoding.u, e_encoding.v));
            prev_A = A;
            prev_B = B;
        } // for edges encoding

        // 处理最后一对超边
        if (!edges_set.isEmpty()) {
            if(prev_A <= prev_B){
                double edges_compare_cond = 0;
                if (prev_A == prev_B) {
                    edges_compare_cond = (superNodeLength(prev_A) * (superNodeLength(prev_A) - 1)) / 4.0;
                } else {
                    edges_compare_cond = (superNodeLength(prev_A) * superNodeLength(prev_B)) / 2.0;
                }
                // 不形成超边
                if (edges_set.size() <= edges_compare_cond) {
                    // 每条边加入到C+集合
                    for (Pair<Integer, Integer> edge : edges_set) {
                        if (edge.getValue0() <= edge.getValue1()) {
                            Cp_0.add(edge.getValue0());
                            Cp_1.add(edge.getValue1());
                        }else{
                            Cp_0.add(edge.getValue1());
                            Cp_1.add(edge.getValue0());
                        }
                    }
                }
                // 行成超边
                else {
                    // 加入超边 <prev_A, prev_B>
                    P.add(new Pair(prev_A, prev_B));
                    if (prev_A == prev_B) {
                        int[] in_A = recoverSuperNode(prev_A);
                        for (int a = 0; a < in_A.length; a++) {
                            for (int b = a + 1; b < in_A.length; b++) {
                                Pair<Integer, Integer> edge_1 = new Pair<>(in_A[a], in_A[b]);
                                Pair<Integer, Integer> edge_2 = new Pair<>(in_A[b], in_A[a]);
                                if (!edges_set.contains(edge_1) && !edges_set.contains(edge_2)) {
                                    if (in_A[a] <= in_A[b]) {
                                        Cm_0.add(in_A[a]);
                                        Cm_1.add(in_A[b]);
                                    } else {
                                        Cm_0.add(in_A[b]);
                                        Cm_1.add(in_A[a]);
                                    }
                                }
                            }
                        }
                    } else {
                        int[] in_A = recoverSuperNode(prev_A);
                        int[] in_B = recoverSuperNode(prev_B);
                        for (int a = 0; a < in_A.length; a++) {
                            for (int b = 0; b < in_B.length; b++) {
                                Pair<Integer, Integer> edge_1 = new Pair<>(in_A[a], in_B[b]);
                                Pair<Integer, Integer> edge_2 = new Pair<>(in_B[b], in_A[a]);
                                if (!edges_set.contains(edge_1) && !edges_set.contains(edge_2)) {
                                    if (in_A[a] <= in_B[b]) {
                                        Cm_0.add(in_A[a]);
                                        Cm_1.add(in_B[b]);
                                    } else {
                                        Cm_0.add(in_B[b]);
                                        Cm_1.add(in_A[a]);
                                    }
                                }  // if
                            }  // for b
                        } // for a
                    } // else
                } // else
            } // if
        }

        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    /**
     * 新的编码函数，与之前不同在与对自环进行了修改，并且用了新的结构来存储边结构，能够用于后续的恢复邻居
     *
     * @return 运行时间
     */
    public double encodePhase_test(){
        logger_.info("开始进行边最优编码, 同时采用了P_neighbors 和 P 两种方式...");
//        System.out.println("# Encode Phase");
        long startTime = System.currentTimeMillis();

        P_neighbors = new HashMap<>();
        Cp_neighbors = new HashMap<>();
        Cm_neighbors = new HashMap<>();
        P = new ArrayList<>();
        Cp_0 = new TIntArrayList();
        Cp_1 = new TIntArrayList();
        Cm_0 = new TIntArrayList();
        Cm_1 = new TIntArrayList();

        LinkedList<FourTuple> edges_encoding = new LinkedList<>();
        for (int node = 0; node < n; node++) {
            for(int neighbor : neighbors_[node]) {
//            for(int neighbor : Gr.successorArray(node)){
                if (node <= neighbor) {
                    if(S[node] <= S[neighbor]){
                        edges_encoding.add(new FourTuple(S[node], S[neighbor], node, neighbor));
                    }else{
                        edges_encoding.add(new FourTuple(S[neighbor], S[node], neighbor, node));
                    }
                }
            }
        }
        Collections.sort(edges_encoding);

        int prev_A = edges_encoding.get(0).A;
        int prev_B = edges_encoding.get(0).B;
        HashSet<Pair<Integer, Integer>> edges_set = new HashSet<>();
        while (!edges_encoding.isEmpty()) {
            FourTuple e_encoding = edges_encoding.pop();
            int A = e_encoding.A;
            int B = e_encoding.B;
            if ((A != prev_A || B != prev_B)) {
                if (prev_A <= prev_B) {
                    double edges_compare_cond = 0;
                    if(prev_A == prev_B){
                        edges_compare_cond = (superNodeLength(prev_A) * (superNodeLength(prev_A)-1)) / 4.0;
                    }else{
                        edges_compare_cond = (superNodeLength(prev_A) * superNodeLength(prev_B)) / 2.0;
                    }

                    // 不形成超边
                    if(edges_set.size() <= edges_compare_cond){
                        for(Pair<Integer, Integer> edge : edges_set){
                            if (edge.getValue0() <= edge.getValue1()) {
                                Cp_0.add(edge.getValue0());
                                Cp_1.add(edge.getValue1());
                            }else{
                                Cp_0.add(edge.getValue1());
                                Cp_1.add(edge.getValue0());
                            }
                            if(!Cp_neighbors.containsKey(edge.getValue0()))
                                Cp_neighbors.put(edge.getValue0(), new ArrayList<>());
                            if(!Cp_neighbors.containsKey(edge.getValue1()))
                                Cp_neighbors.put(edge.getValue1(), new ArrayList<>());
                            Cp_neighbors.get(edge.getValue0()).add(edge.getValue1());
                            Cp_neighbors.get(edge.getValue1()).add(edge.getValue0());
                        }
                    }else{
                        P.add(new Pair(prev_A, prev_B));
                        if(!P_neighbors.containsKey(prev_A))
                            P_neighbors.put(prev_A, new ArrayList<>());
                        if(!P_neighbors.containsKey(prev_B))
                            P_neighbors.put(prev_B, new ArrayList<>());
                        P_neighbors.get(prev_A).add(prev_B);
                        P_neighbors.get(prev_B).add(prev_A);
                        if(prev_A == prev_B){
                            int[] in_A = recoverSuperNode(prev_A);
                            for (int a = 0; a < in_A.length; a++) {
                                for (int b = a + 1; b < in_A.length; b++) {
                                    Pair<Integer, Integer> edge_1 = new Pair<>(in_A[a], in_A[b]);
                                    Pair<Integer, Integer> edge_2 = new Pair<>(in_A[b], in_A[a]);
                                    if( !edges_set.contains(edge_1) && !edges_set.contains(edge_2) ){
                                        if (in_A[a] <= in_A[b]) {
                                            Cm_0.add(in_A[a]);
                                            Cm_1.add(in_A[b]);
                                        } else {
                                            Cm_0.add(in_A[b]);
                                            Cm_1.add(in_A[a]);
                                        }
                                        if(!Cm_neighbors.containsKey(in_A[a]))
                                            Cm_neighbors.put(in_A[a], new ArrayList<>());
                                        if(!Cm_neighbors.containsKey(in_A[b]))
                                            Cm_neighbors.put(in_A[b], new ArrayList<>());
                                        Cm_neighbors.get(in_A[a]).add(in_A[b]);
                                        Cm_neighbors.get(in_A[b]).add(in_A[a]);
                                    }
                                }
                            }
                        }else{
                            int[] in_A = recoverSuperNode(prev_A);
                            int[] in_B = recoverSuperNode(prev_B);
                            for (int a = 0; a < in_A.length; a++) {
                                for (int b = 0; b < in_B.length; b++) {
                                    Pair<Integer, Integer> edge_1 = new Pair<>(in_A[a], in_B[b]);
                                    Pair<Integer, Integer> edge_2 = new Pair<>(in_B[b], in_A[a]);
                                    if (!edges_set.contains(edge_1) && !edges_set.contains(edge_2)) {
                                        if (in_A[a] <= in_B[b]) {
                                            Cm_0.add(in_A[a]);
                                            Cm_1.add(in_B[b]);
                                        } else {
                                            Cm_0.add(in_B[b]);
                                            Cm_1.add(in_A[a]);
                                        }
                                        if (!Cm_neighbors.containsKey(in_A[a]))
                                            Cm_neighbors.put(in_A[a], new ArrayList<>());
                                        if (!Cm_neighbors.containsKey(in_B[b]))
                                            Cm_neighbors.put(in_B[b], new ArrayList<>());
                                        Cm_neighbors.get(in_A[a]).add(in_B[b]);
                                        Cm_neighbors.get(in_B[b]).add(in_A[a]);
                                    }
                                }
                            }
                        }
                    }
                }
                edges_set = new HashSet<>();
            }
            edges_set.add(new Pair(e_encoding.u, e_encoding.v));
            prev_A = A;
            prev_B = B;
        }

        if (edges_set.size() != 0) {
            if (prev_A <= prev_B) {
                double edges_compare_cond = 0;
                if(prev_A == prev_B){
                    edges_compare_cond = (superNodeLength(prev_A) * (superNodeLength(prev_A)-1)) / 4.0;
                }else{
                    edges_compare_cond = (superNodeLength(prev_A) * superNodeLength(prev_B)) / 2.0;
                }

                // 不形成超边
                if(edges_set.size() <= edges_compare_cond){
                    for(Pair<Integer, Integer> edge : edges_set){
                        if (edge.getValue0() <= edge.getValue1()) {
                            Cp_0.add(edge.getValue0());
                            Cp_1.add(edge.getValue1());
                        }else{
                            Cp_0.add(edge.getValue1());
                            Cp_1.add(edge.getValue0());
                        }
                        if(!Cp_neighbors.containsKey(edge.getValue0()))
                            Cp_neighbors.put(edge.getValue0(), new ArrayList<>());
                        if(!Cp_neighbors.containsKey(edge.getValue1()))
                            Cp_neighbors.put(edge.getValue1(), new ArrayList<>());
                        Cp_neighbors.get(edge.getValue0()).add(edge.getValue1());
                        Cp_neighbors.get(edge.getValue1()).add(edge.getValue0());
                    }
                }else{
                    P.add(new Pair(prev_A, prev_B));
                    if(!P_neighbors.containsKey(prev_A))
                        P_neighbors.put(prev_A, new ArrayList<>());
                    if(!P_neighbors.containsKey(prev_B))
                        P_neighbors.put(prev_B, new ArrayList<>());
                    P_neighbors.get(prev_A).add(prev_B);
                    P_neighbors.get(prev_B).add(prev_A);
                    if(prev_A == prev_B){
                        int[] in_A = recoverSuperNode(prev_A);
                        for (int a = 0; a < in_A.length; a++) {
                            for (int b = a + 1; b < in_A.length; b++) {
                                Pair<Integer, Integer> edge_1 = new Pair<>(in_A[a], in_A[b]);
                                Pair<Integer, Integer> edge_2 = new Pair<>(in_A[b], in_A[a]);
                                if( !edges_set.contains(edge_1) && !edges_set.contains(edge_2) ){
                                    if (in_A[a] <= in_A[b]) {
                                        Cm_0.add(in_A[a]);
                                        Cm_1.add(in_A[b]);
                                    } else {
                                        Cm_0.add(in_A[b]);
                                        Cm_1.add(in_A[a]);
                                    }
                                    if(!Cm_neighbors.containsKey(in_A[a]))
                                        Cm_neighbors.put(in_A[a], new ArrayList<>());
                                    if(!Cm_neighbors.containsKey(in_A[b]))
                                        Cm_neighbors.put(in_A[b], new ArrayList<>());
                                    Cm_neighbors.get(in_A[a]).add(in_A[b]);
                                    Cm_neighbors.get(in_A[b]).add(in_A[a]);
                                }
                            }
                        }
                    }else{
                        int[] in_A = recoverSuperNode(prev_A);
                        int[] in_B = recoverSuperNode(prev_B);
                        for (int a = 0; a < in_A.length; a++) {
                            for (int b = 0; b < in_B.length; b++) {
                                Pair<Integer, Integer> edge_1 = new Pair<>(in_A[a], in_B[b]);
                                Pair<Integer, Integer> edge_2 = new Pair<>(in_B[b], in_A[a]);
                                if (!edges_set.contains(edge_1) && !edges_set.contains(edge_2)) {
                                    if (in_A[a] <= in_B[b]) {
                                        Cm_0.add(in_A[a]);
                                        Cm_1.add(in_B[b]);
                                    } else {
                                        Cm_0.add(in_B[b]);
                                        Cm_1.add(in_A[a]);
                                    }
                                    if (!Cm_neighbors.containsKey(in_A[a]))
                                        Cm_neighbors.put(in_A[a], new ArrayList<>());
                                    if (!Cm_neighbors.containsKey(in_B[b]))
                                        Cm_neighbors.put(in_B[b], new ArrayList<>());
                                    Cm_neighbors.get(in_A[a]).add(in_B[b]);
                                    Cm_neighbors.get(in_B[b]).add(in_A[a]);
                                }
                            }
                        }
                    }
                }
            }
        }

        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    /**
     * 评价函数，用于评估压缩性能，输出格式为：
     * @Compression: 0.xxxxx
     * @nodes: xxxxx ===> xxxxx
     * @edges: xxxxx ===> xxxxx(P:xxx, C+:xxx, C-:xxx)
     */
    public void evaluatePhase(){
        logger_.info("开始性能评估, 同时统计了 P_neighbors 和 P 两种方式...");
//        System.out.println("# Evaluate Phase");
        int sp_num = 0;
        for (int i = 0; i < n; i++) {
            if(I[i] != -1) sp_num++;
        }

        int P_num = 0;
        for (Integer key : P_neighbors.keySet()) {
            P_num += P_neighbors.get(key).size();
        }

        int Cp_num = 0;
        for (Integer key : Cp_neighbors.keySet()) {
            Cp_num += Cp_neighbors.get(key).size();
        }

        int Cm_num = 0;
        for (Integer key : Cm_neighbors.keySet()) {
            Cm_num += Cm_neighbors.get(key).size();
        }

        int total = 0;
        for (int i = 0; i < n; i++) {
            int[] neighbors = neighbors_[i];
//            int[] neighbors = Gr.successorArray(i);
            total += neighbors.length;
        }

        logger_.info(String.format("#Nodes: %6d ===> %6d", Gr.numNodes(), sp_num));
        logger_.info(String.format("#Edges(P): %6d ===> %6d(P:%d, C+:%d, C-:%d)", total, 2 * (P.size() + Cp_0.size() + Cm_0.size()), P.size(), Cp_0.size(), Cm_0.size()));
        logger_.info(String.format("#Edges(P): %6d ===> %6d(P:%d, C+:%d, C-:%d)", total, (P_num + Cp_num + Cm_num), P_num , Cp_num, Cm_num));
        logger_.info(String.format("#Compression: %6f(P), %6f(P_neighbors)", (1 - (P.size() + Cp_0.size() + Cm_0.size()) * 2.0 / total), (1 - (P_num + Cp_num + Cm_num) * 1.0 / total)));
    }

    /**
     * 计算当前的压缩率
     *
     * @return 压缩后的边数量
     */
    public int calculateCompression(){
        int edges = P.size() + Cp_0.size() + Cm_0.size();
//        System.out.println("Edges:" + edges  + ",Compression:" + (1 - (edges*1.0) / Gr.numArcs()));
        return edges;
    }

    /**
     * 通过Summary Graph来恢复某个点u的邻居集合
     * 参数method=new, 对应使用了数据结构 P, Cp_0, Cp_1, Cm_0 和 Cm_1
     * 参数method=test, 对应使用了数据结构 P_neighbors, Cp_neighbors 和 Cm_neighbors
     *
     * @param u 顶点u的编号
     * @param method 使用哪个方式: new , test
     * @return 邻居集合
     */
    public Set<Integer> recoverNeighbors(int u, String method) {
        Set<Integer> neighbors = new TreeSet<>();
        // 恢复顶点u的所有顶点
        int A = S[u];

        if (method.equals("new")) {
            // 处理超边邻居和自环边
            for (Pair<Integer, Integer> superEdge : P) {
                if(superEdge.getValue0() == A){
                    int[] in_B = recoverSuperNode(superEdge.getValue1());
                    for(int node : in_B){
                        if(node == u) continue;
                        neighbors.add(node);
                    }
                } else if(superEdge.getValue1() == A){
                    int[] in_B = recoverSuperNode(superEdge.getValue0());
                    for (int node : in_B) {
                        if(node == u) continue;
                        neighbors.add(node);
                    }
                }
            }
            // 处理Cplus邻居
            for (int i = 0; i < Cp_0.size(); i++) {
                if (Cp_0.get(i) == u && Cp_1.get(i) != u) {
                    neighbors.add(Cp_1.get(i));
                } else if (Cp_1.get(i) == u && Cp_0.get(i) != u) {
                    neighbors.add(Cp_0.get(i));
                }
            }
            // 处理Cminus邻居
            for (int i = 0; i < Cm_0.size(); i++) {
                if (Cm_0.get(i) == u && Cm_1.get(i) != u) {
                    neighbors.remove(Cm_1.get(i));
                } else if (Cm_1.get(i) == u && Cm_0.get(i) != u) {
                    neighbors.remove(Cm_0.get(i));
                }
            }
        }else{
            // 处理超边邻居和自环边
            if(P_neighbors.containsKey(A)) {
                for (Integer B : P_neighbors.get(A)) {
                    int[] in_B = recoverSuperNode(B);
                    for (int node : in_B) {
                        if (node == u) continue;
                        neighbors.add(node);
                    }
                }
            }
            // 处理Cplus邻居
            if(Cp_neighbors.containsKey(u)) {
                for (Integer node : Cp_neighbors.get(u)) {
                    if (node == u) continue;
                    neighbors.add(node);
                }
            }
            // 处理Cminus邻居
            if(Cm_neighbors.containsKey(u)) {
                for (Integer node : Cm_neighbors.get(u)) {
                    neighbors.remove(node);
                }
            }
        }

        return neighbors;
    }

    /**
     * 测试合并后的summary graph是否能正确恢复顶点的邻居集合
     */
    public void testRecoverNeighbors(int num, String method){
        logger_.info("开始测试顶点的邻居集合恢复性能");
        double origin_time = 0, recover_time = 0;
        long startTime = 0;
        List<Integer> wrongNodes = new ArrayList<>();
        int correct = 0, wrong = 0;
        for (int i = 0; i < num; i++) {
            startTime = System.currentTimeMillis();
            int[] origin_ = neighbors_[i];
//            int[] origin_ = Gr.successorArray(i);
            origin_time += (System.currentTimeMillis() - startTime) / 1000.0;
            startTime = System.currentTimeMillis();
            Set<Integer> summary_ = recoverNeighbors(i, method);
            recover_time += (System.currentTimeMillis() - startTime) / 1000.0;
            boolean flag = true;
            for (int node : origin_) {
                if(!summary_.contains(node)){
                    flag = false;
                    continue;
                }
                summary_.remove(node);
            }
            if (!flag || summary_.size() != 0) {
                wrong++;
                wrongNodes.add(i);
            }else{
                correct++;
            }
        }
        logger_.info(String.format("测试结束, 总计测试顶点 %d, 正确个数 %d, 错误个数 %d", (correct + wrong), correct, wrong));
        logger_.info(String.format("通过数组获取顶点邻居花费时间 %f seconds, 平均每个顶点花费 %f seconds", origin_time, (origin_time / num)));
        logger_.info(String.format("通过P_neighbors获取顶点邻居花费时间 %f seconds, 平均每个顶点花费 %f seconds", recover_time, (recover_time / num)));
        checkoutWrongNeighbors(wrongNodes, method);
    }

    /**
     * 对邻居恢复出错的顶点进行debug
     * @param wrongNodes 错误的顶点集合
     * @param method 采用的方式: new , test
     */
    public void checkoutWrongNeighbors(List<Integer> wrongNodes, String method) {
//        for(Integer u : wrongNodes){
//            int[] origin_ = neighbors_[u];
//            int[] origin_ = Gr.successorArray(u);
//            Set<Integer> summary_;
//            if(method.equals("test")){
//                summary_ = recoverNeighbors_test(u);
//            } else{
//                summary_ = recoverNeighbors_new(u);
//            }
//            System.out.print(u + "'s degree:" + Gr.outdegree(u) + ", origin neighbors contain:");
//            for (int node : origin_) {
//                System.out.print(node + " ");
//            }
//            System.out.print("\n" + u + "'s recover neighbors contain:");
//            for (Integer node : summary_) {
//                System.out.print(node + " ");
//            }
//            System.out.println();
//        }
        if(wrongNodes.size() == 0) return;
        logger_.debug("顶点的邻居恢复出现问题, 开始检查");
        int u = wrongNodes.get(0);
        int[] origin_ = neighbors_[u];
//        int[] origin_ = Gr.successorArray(u);
        Set<Integer> summary_ = recoverNeighbors(u, method);
        StringBuilder origin_neighbors = new StringBuilder();
        origin_neighbors.append(u).append("'s origin neighbors contains:");
        for(int node : origin_){
            origin_neighbors.append(node).append(" ");
        }
        logger_.debug(origin_neighbors.toString());
        StringBuilder recover_neighbors = new StringBuilder();
        recover_neighbors.append(u).append("'s recover neighbors contains:");
        for (int node : summary_) {
            recover_neighbors.append(node).append(" ");
        }
        logger_.debug(recover_neighbors.toString());
    }

    /**
     * 用于 Lossy Summarization 的情形，目前没有使用到
     *
     * @param error_bound 对边进行丢弃的阈值参数
     */
    public void dropPhase(double error_bound) {
        System.out.println("# Drop Phase");

        double[] cv = new double[n];
        for (int i = 0; i < n; i++) {
            cv[i] = error_bound * Gr.outdegree(i);
        }

        TIntArrayList updated_Cp_0 = new TIntArrayList();
        TIntArrayList updated_Cp_1 = new TIntArrayList();
        for (int i = 0; i < Cp_0.size(); i++) {
            int edge_u = Cp_0.get(i);
            int edge_v = Cp_1.get(i);

            if (cv[edge_u] >= 1 && cv[edge_v] >= 1) {
                cv[edge_u] = cv[edge_u] - 1;
                cv[edge_v] = cv[edge_v] - 1;
            } else {
                updated_Cp_0.add(edge_u);
                updated_Cp_1.add(edge_v);
            }
        }
        Cp_0 = updated_Cp_0;
        Cp_1 = updated_Cp_1;

        TIntArrayList updated_Cm_0 = new TIntArrayList();
        TIntArrayList updated_Cm_1 = new TIntArrayList();
        for (int i = 0; i < Cm_0.size(); i++) {
            int edge_u = Cm_0.get(i);
            int edge_v = Cm_1.get(i);

            if (cv[edge_u] >= 1 && cv[edge_v] >= 1) {
                cv[edge_u] = cv[edge_u] - 1;
                cv[edge_v] = cv[edge_v] - 1;
            } else {
                updated_Cm_0.add(edge_u);
                updated_Cm_1.add(edge_v);
            }
        }
        Cm_0 = updated_Cm_0;
        Cm_1 = updated_Cm_1;

        Collections.sort(P, new EdgeCompare(supernode_sizes));
        ArrayList<Pair<Integer, Integer>> updated_P = new ArrayList<Pair<Integer, Integer>>();
        for (Pair<Integer, Integer> edge : P) {
            int A = edge.getValue0();
            int B = edge.getValue1();

            if (A == B) {
                updated_P.add(edge);
                continue;
            }

            int size_B = supernode_sizes[B];
            boolean cond_A = true;
            TIntArrayList in_A = sn_to_n.get(A);

            for (int i = 0; i < in_A.size(); i++) {
                if (cv[in_A.get(i)] < size_B) {
                    cond_A = false;
                    break;
                }
            }
            if (!cond_A) {
                updated_P.add(edge);
                continue;
            }

            int size_A = supernode_sizes[A];
            boolean cond_B = true;
            TIntArrayList in_B = sn_to_n.get(B);

            for (int i = 0; i < in_B.size(); i++) {
                if (cv[in_B.get(i)] < size_A) {
                    cond_B = false;
                    break;
                }
            }
            if (!cond_B) {
                updated_P.add(edge);
                continue;
            }

            // if conditions are all true, ie (A != B && all v in A && all v in B)
            for (int i = 0; i < in_A.size(); i++) {
                cv[in_A.get(i)] = cv[in_A.get(i)] - size_B;
            }
            for (int i = 0; i < in_B.size(); i++) {
                cv[in_B.get(i)] = cv[in_B.get(i)] - size_A;
            }

        }
        P = updated_P;
        System.out.println("Drop Compression: " + (1 - (P.size() + Cp_0.size() + Cm_0.size() * 1.0) / (Gr.numArcs() / 2 * 1.0)));
    }

    /**
     * 这个函数用于检查数据集是否有自环边，如果存在则有问题
     */
    public void checkDataSet(){
        int self_loop = 0;
        int arcs = 0;
        for (int u = 0; u < n; u++) {
            int[] Neigh = Gr.successorArray(u);
            for (int v : Neigh) {
                if(v == u) self_loop++;
                arcs++;
            }
        }
        logger_.info(String.format("总顶点数量: %d, 总边数: %d, 自环边: %d", n, arcs, self_loop));
    }

    /**
     * @param iteration              迭代次数
     * @param print_iteration_offset 每执行多少次迭代就进行一次 encode 和 evaluate 进行结果输出
     */
    public void run(int iteration, int print_iteration_offset) {

    }

    public void run(int iteration, int print_iteration_offset, int max_group_size, int hierarchical_k){

    }


    public void originTest(){

    }

    public void splitTest(){

    }
}
