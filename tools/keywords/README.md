# Keyword index

`build_keyword_index.py` builds `app/src/main/assets/keyword_index.bin`, which the on-device keyword
tagger ranks against a SigLIP 2 image embedding.

```bash
pip install -r requirements.txt
python build_keyword_index.py            # full build, about 10 minutes on 16 cores
python build_keyword_index.py --export   # rewrite the .bin from the cached index.npz
```

It picks common English nouns and adjectives plus short Open Images phrases, embeds them with the
SigLIP 2 text tower (open_clip `ViT-B-16-SigLIP2`), and records each term's score spread over 300
random photos from picsum.photos. The app ranks terms by how unusual their score is for the photo
at hand, so words that match almost any photo do not crowd out the specific ones.

`extra_terms.txt` adds common stock terms the word filter misses, hand-picked from the
[Recognize Anything](https://github.com/xinyu1205/recognize-anything) tag list (Apache-2.0),
[Places365](https://github.com/CSAILVision/places365) scene names (MIT),
[Kinetics-700](https://github.com/google-deepmind/kinetics-i3d) activity names (CC BY 4.0),
[Tencent ML-Images](https://github.com/Tencent/tencent-ml-images) categories (CC BY 4.0),
[LVIS](https://www.lvisdataset.org/), [Visual Genome](https://homes.cs.washington.edu/~ranjay/visualgenome/)
and Open Images class names (CC BY 4.0), WordNet everyday categories and microstock topic lists.
Additions that duplicate an existing term (text-embedding cosine above 0.975) or that fired on the
wrong photos in held-out checks were left out.
`exclude.txt` removes terms that are not visible in a photo or that the tagger guesses wrong.

The text tower must match the image model the app downloads, `litert-community/SigLIP2-base-patch16-224`.
