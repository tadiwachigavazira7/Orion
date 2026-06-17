export const colors = {
  light: {
    background: 'white',
    border: 'black',
    text: 'black',
    subtext: '#999999',
    card: '#C4C4C4',
  },
  dark: {
    background: '#1a1a1a',
    border: 'white',
    text: 'white',
    subtext: '#aaaaaa',
    card: '#333333',
  }
}

const isDarkMode = false; // we'll hook this up to a real toggle later

export const theme = isDarkMode ? colors.dark : colors.light;